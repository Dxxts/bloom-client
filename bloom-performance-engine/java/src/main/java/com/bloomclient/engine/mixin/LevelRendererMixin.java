package com.bloomclient.engine.mixin;

import com.bloomclient.engine.profiling.RenderStats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measurement only: submit of entities, block entities and particles,
 * feature preparation and frame graph execution (where the GPU commands are
 * recorded). Every wrapped call runs unchanged.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Inject(method = "submitEntities", at = @At("HEAD"))
	private void bloom$entitiesStart(PoseStack poseStack, LevelRenderState state, SubmitNodeCollector output, CallbackInfo ci, @Share("t") LocalLongRef t) {
		t.set(RenderStats.begin());
	}

	@Inject(method = "submitEntities", at = @At("RETURN"))
	private void bloom$entitiesEnd(PoseStack poseStack, LevelRenderState state, SubmitNodeCollector output, CallbackInfo ci, @Share("t") LocalLongRef t) {
		RenderStats.end(RenderStats.Stage.SUBMIT_ENTITIES, t.get());
	}

	@Inject(method = "submitBlockEntities", at = @At("HEAD"))
	private void bloom$blockEntitiesStart(PoseStack poseStack, LevelRenderState state, SubmitNodeCollector output, CallbackInfo ci,
			@Share("t") LocalLongRef t) {
		t.set(RenderStats.begin());
	}

	@Inject(method = "submitBlockEntities", at = @At("RETURN"))
	private void bloom$blockEntitiesEnd(PoseStack poseStack, LevelRenderState state, SubmitNodeCollector output, CallbackInfo ci,
			@Share("t") LocalLongRef t) {
		RenderStats.end(RenderStats.Stage.SUBMIT_BLOCK_ENTITIES, t.get());
	}

	@WrapOperation(method = "submitFeatures", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
	private void bloom$particles(ParticlesRenderState particles, SubmitNodeCollector output, CameraRenderState camera, Operation<Void> original) {
		long t = RenderStats.begin();
		original.call(particles, output, camera);
		RenderStats.end(RenderStats.Stage.SUBMIT_PARTICLES, t);
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
	private FeatureRenderDispatcher.PreparedFrame bloom$prepare(FeatureRenderDispatcher dispatcher, SubmitNodeStorage storage,
			Operation<FeatureRenderDispatcher.PreparedFrame> original) {
		long t = RenderStats.begin();
		try {
			return original.call(dispatcher, storage);
		} finally {
			RenderStats.end(RenderStats.Stage.PREPARE_FEATURES, t);
		}
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
	private void bloom$frameGraph(FrameGraphBuilder frame, GraphicsResourceAllocator allocator, FrameGraphBuilder.Inspector inspector,
			Operation<Void> original) {
		long t = RenderStats.begin();
		original.call(frame, allocator, inspector);
		RenderStats.end(RenderStats.Stage.EXECUTE_FRAME_GRAPH, t);
	}
}
