package com.bloomclient.engine.vulkan;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.EngineConfig;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

/**
 * GPU selection for Minecraft's Vulkan backend. Vanilla takes the first
 * suitable device and prefers a discrete GPU; with a preference
 * ({@code vulkanDevice} in {@code config/bloom-engine.json} or
 * {@code -Dbloom.vulkan.device}, set by the launcher) Bloom picks the device
 * whose index or name matches, if Minecraft's own suitability check accepts
 * it. Otherwise vanilla's choice stands.
 */
public final class VulkanDeviceSelection {
	public record Candidate(int index, String name, boolean discrete, @Nullable String unsuitableBecause) {
	}

	private static volatile List<Candidate> candidates = List.of();
	private static volatile String outcome = "Minecraft's default choice";

	private VulkanDeviceSelection() {
	}

	/** Preference from the system property, then from the configuration file. */
	public static String preference() {
		String property = System.getProperty("bloom.vulkan.device");
		if (property != null) {
			return property.trim();
		}
		try {
			return EngineConfig.load(FabricLoader.getInstance().getConfigDir().resolve("bloom-engine.json")).vulkanDevice.trim();
		} catch (Throwable t) {
			return "";
		}
	}

	/**
	 * Returns the device to use, or {@code null} to keep vanilla's selection.
	 *
	 * @param suitability Minecraft's {@code checkDeviceSuitability}: {@code null} when suitable
	 */
	public static @Nullable VkPhysicalDevice choose(VkInstance instance, Function<VkPhysicalDevice, @Nullable String> suitability,
			Function<VkPhysicalDevice, String> name, Predicate<VkPhysicalDevice> discrete) {
		List<VkPhysicalDevice> devices = new ArrayList<>();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer count = stack.mallocInt(1);
			if (VK10.vkEnumeratePhysicalDevices(instance, count, null) != VK10.VK_SUCCESS || count.get(0) == 0) {
				return null;
			}
			PointerBuffer handles = stack.mallocPointer(count.get(0));
			if (VK10.vkEnumeratePhysicalDevices(instance, count, handles) != VK10.VK_SUCCESS) {
				return null;
			}
			for (int i = 0; i < count.get(0); i++) {
				devices.add(new VkPhysicalDevice(handles.get(i), instance));
			}
		}
		List<Candidate> found = new ArrayList<>();
		for (int i = 0; i < devices.size(); i++) {
			VkPhysicalDevice d = devices.get(i);
			found.add(new Candidate(i, name.apply(d), discrete.test(d), suitability.apply(d)));
		}
		candidates = List.copyOf(found);

		String wanted = preference();
		if (wanted.isEmpty()) {
			outcome = "Minecraft's default choice";
			return null;
		}
		for (Candidate c : found) {
			if (matches(c, wanted)) {
				if (c.unsuitableBecause() != null) {
					outcome = "'" + wanted + "' matches " + c.name() + ", which Minecraft rejects (" + c.unsuitableBecause() + "): default choice";
					BloomEngine.LOGGER.warn("Bloom Engine: Vulkan device {}", outcome);
					return null;
				}
				outcome = "selected by Bloom: " + c.name() + " (preference '" + wanted + "')";
				BloomEngine.LOGGER.info("Bloom Engine: Vulkan device {}", outcome);
				return devices.get(c.index());
			}
		}
		outcome = "no device matches '" + wanted + "': default choice";
		BloomEngine.LOGGER.warn("Bloom Engine: Vulkan device {}", outcome);
		return null;
	}

	static boolean matches(Candidate c, String wanted) {
		try {
			return Integer.parseInt(wanted) == c.index();
		} catch (NumberFormatException e) {
			return c.name().toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT));
		}
	}

	public static List<Candidate> candidates() {
		return candidates;
	}

	public static String outcome() {
		return outcome;
	}
}
