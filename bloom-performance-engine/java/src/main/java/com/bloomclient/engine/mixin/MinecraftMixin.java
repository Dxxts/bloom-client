package com.bloomclient.engine.mixin;

import com.bloomclient.engine.benchmark.BenchmarkController;
import com.bloomclient.engine.diagnostics.BloomDebugEntries;
import com.bloomclient.engine.profiling.EngineProfiler;
import com.bloomclient.engine.profiling.GpuFrameTimer;
import com.bloomclient.engine.profiling.RenderStats;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Frame timing (CPU and GPU) for the profiler and the benchmark driver. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Unique
	private boolean bloom$defaultsApplied;

	@Inject(method = "runTick", at = @At("HEAD"))
	private void bloom$frameStart(boolean advanceGameTime, CallbackInfo ci) {
		if (!bloom$defaultsApplied) {
			bloom$defaultsApplied = true;
			BloomDebugEntries.applyDefaultsOnce((Minecraft) (Object) this);
		}
		EngineProfiler.frameStart();
		BenchmarkController.tick((Minecraft) (Object) this);
		GpuFrameTimer.beginFrame();
	}

	@Inject(method = "runTick", at = @At("RETURN"))
	private void bloom$frameEnd(boolean advanceGameTime, CallbackInfo ci) {
		EngineProfiler.frameEnd();
		GpuFrameTimer.endFrame();
		RenderStats.endFrame();
	}
}
