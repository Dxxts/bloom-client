package com.bloomclient.engine.mixin;

import com.bloomclient.engine.vulkan.VulkanDeviceSelection;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import java.util.Set;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets Bloom choose the GPU of Minecraft's Vulkan backend. The device must
 * pass Minecraft's own suitability check; otherwise vanilla selects as usual.
 */
@Mixin(VulkanBackend.class)
abstract class VulkanBackendMixin {
	@Shadow
	private static BackendCreationException checkDeviceSuitability(VkPhysicalDevice device, Set<FeatureSet> required, Set<FeatureSet> requiredIfAvailable)
			throws BackendCreationException {
		throw new AssertionError();
	}

	@Shadow
	private static String getDeviceName(VkPhysicalDevice device) {
		throw new AssertionError();
	}

	@Shadow
	private static boolean isDeviceDiscrete(VkPhysicalDevice device) {
		throw new AssertionError();
	}

	@Inject(method = "findPhysicalDevice", at = @At("HEAD"), cancellable = true)
	private static void bloom$selectDevice(VulkanInstance instance, Set<FeatureSet> required, Set<FeatureSet> requiredIfAvailable,
			CallbackInfoReturnable<VulkanPhysicalDevice> cir) throws BackendCreationException {
		VkPhysicalDevice chosen = VulkanDeviceSelection.choose(instance.vkInstance(), device -> {
			try {
				BackendCreationException problem = checkDeviceSuitability(device, required, requiredIfAvailable);
				return problem == null ? null : problem.getMessage();
			} catch (BackendCreationException e) {
				return e.getMessage();
			}
		}, VulkanBackendMixin::getDeviceName, VulkanBackendMixin::isDeviceDiscrete);
		if (chosen != null) {
			cir.setReturnValue(new VulkanPhysicalDevice(chosen));
		}
	}
}
