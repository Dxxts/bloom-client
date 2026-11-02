package com.bloomclient.engine.mixin;

import com.bloomclient.engine.access.BloomGpuDevice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes the backend behind {@code renderpearl}'s frontend device, read-only (diagnostics). */
@Mixin(FrontendGpuDevice.class)
abstract class FrontendGpuDeviceMixin implements BloomGpuDevice {
	@Shadow @Final private GpuDeviceBackend backend;

	@Override
	public Object bloom$backend() {
		return backend;
	}
}
