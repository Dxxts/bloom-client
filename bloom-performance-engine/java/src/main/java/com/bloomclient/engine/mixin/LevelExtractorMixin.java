package com.bloomclient.engine.mixin;

import com.bloomclient.engine.profiling.RenderStats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measurement only: time spent extracting entity, block entity and particle
 * render states (the per-object Java logic: animation, interpolation).
 * Nothing is changed.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
	@Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void bloom$entitiesStart(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo ci,
			@Share("t") LocalLongRef t) {
		t.set(RenderStats.begin());
	}

	@Inject(method = "extractVisibleEntities", at = @At("RETURN"))
	private void bloom$entitiesEnd(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo ci,
			@Share("t") LocalLongRef t) {
		RenderStats.end(RenderStats.Stage.EXTRACT_ENTITIES, t.get());
		RenderStats.entities(output.entityRenderStates.size());
	}

	@Inject(method = "extractVisibleBlockEntities", at = @At("HEAD"))
	private void bloom$blockEntitiesStart(Camera camera, float partialTick, LevelRenderState output, CallbackInfo ci, @Share("t") LocalLongRef t) {
		t.set(RenderStats.begin());
	}

	@Inject(method = "extractVisibleBlockEntities", at = @At("RETURN"))
	private void bloom$blockEntitiesEnd(Camera camera, float partialTick, LevelRenderState output, CallbackInfo ci, @Share("t") LocalLongRef t) {
		RenderStats.end(RenderStats.Stage.EXTRACT_BLOCK_ENTITIES, t.get());
		RenderStats.blockEntities(output.blockEntityRenderStates.size());
	}

	@WrapOperation(method = "extract", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/particle/ParticleEngine;extract(Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)V"))
	private void bloom$particles(ParticleEngine engine, ParticlesRenderState state, Frustum frustum, Camera camera, float partialTick,
			Operation<Void> original) {
		long t = RenderStats.begin();
		original.call(engine, state, frustum, camera, partialTick);
		RenderStats.end(RenderStats.Stage.EXTRACT_PARTICLES, t);
	}
}
