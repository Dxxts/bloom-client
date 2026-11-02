package com.bloomclient.engine.profiling;

import com.bloomclient.engine.access.BloomGpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

/**
 * Video memory, where the backend can tell (render thread only, sampled at
 * most once a second):
 * <ul>
 *   <li>Vulkan: VMA heap budgets of Minecraft's own allocator (usage and
 *   budget of the device-local heaps).</li>
 *   <li>OpenGL: {@code GL_NVX_gpu_memory_info} (NVIDIA) or
 *   {@code GL_ATI_meminfo} (AMD), read-only queries; other drivers: unknown.</li>
 * </ul>
 */
public final class VramProbe {
	private static final int GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX = 0x9048;
	private static final int GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
	private static final int TEXTURE_FREE_MEMORY_ATI = 0x87FC;

	/** Used and total MiB, or -1 when unknown. */
	public record Sample(long usedMiB, long totalMiB, String source) {
		static final Sample UNKNOWN = new Sample(-1, -1, "not exposed by this backend/driver");
	}

	private static long lastSampleNanos;
	private static Sample last = Sample.UNKNOWN;

	private VramProbe() {
	}

	public static Sample sample() {
		long now = System.nanoTime();
		if (now - lastSampleNanos < 1_000_000_000L) {
			return last;
		}
		lastSampleNanos = now;
		try {
			GpuDevice device = RenderSystem.getDevice();
			Object backend = ((BloomGpuDevice) device).bloom$backend();
			if (backend instanceof VulkanDevice vulkan) {
				last = vulkan(vulkan);
			} else {
				last = openGl(device.getDeviceInfo());
			}
		} catch (Throwable t) {
			last = Sample.UNKNOWN;
		}
		return last;
	}

	private static Sample vulkan(VulkanDevice device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer props = stack.mallocPointer(1);
			Vma.vmaGetMemoryProperties(device.vma(), props);
			VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.create(props.get(0));
			int heaps = memory.memoryHeapCount();
			VmaBudget.Buffer budgets = VmaBudget.malloc(VK10.VK_MAX_MEMORY_HEAPS, stack);
			Vma.vmaGetHeapBudgets(device.vma(), budgets);
			long used = 0;
			long total = 0;
			for (int i = 0; i < heaps; i++) {
				if ((memory.memoryHeaps(i).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
					used += budgets.get(i).usage();
					total += budgets.get(i).budget();
				}
			}
			return new Sample(used >> 20, total >> 20, "Vulkan VMA budget (device-local heaps)");
		}
	}

	private static Sample openGl(DeviceInfo info) {
		// The context's own capabilities: renderpearl lists only the extensions it uses.
		GLCapabilities caps = GL.getCapabilities();
		if (caps.GL_NVX_gpu_memory_info) {
			long total = GL11C.glGetInteger(GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX);
			long free = GL11C.glGetInteger(GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX);
			return new Sample((total - free) >> 10, total >> 10, "GL_NVX_gpu_memory_info (whole GPU)");
		}
		if (caps.GL_ATI_meminfo) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				var values = stack.mallocInt(4);
				GL11C.glGetIntegerv(TEXTURE_FREE_MEMORY_ATI, values);
				return new Sample(-1, values.get(0) >> 10, "GL_ATI_meminfo (free texture memory only)");
			}
		}
		return Sample.UNKNOWN;
	}
}
