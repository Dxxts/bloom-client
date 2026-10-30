package com.bloomclient.engine.mixin;

import com.bloomclient.engine.benchmark.BenchmarkController;
import com.bloomclient.engine.profiling.EngineProfiler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Frame timing for the profiler and the benchmark driver. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void bloom$frameStart(boolean advanceGameTime, CallbackInfo ci) {
		EngineProfiler.frameStart();
		BenchmarkController.tick((Minecraft) (Object) this);
	}

	@Inject(method = "runTick", at = @At("RETURN"))
	private void bloom$frameEnd(boolean advanceGameTime, CallbackInfo ci) {
		EngineProfiler.frameEnd();
	}
}
