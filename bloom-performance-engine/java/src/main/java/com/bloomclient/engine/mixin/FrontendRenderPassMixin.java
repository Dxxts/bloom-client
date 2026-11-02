package com.bloomclient.engine.mixin;

import com.bloomclient.engine.profiling.RenderStats;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import java.nio.IntBuffer;
import java.util.Collection;
import org.lwjgl.PointerBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts draw calls where every backend receives them: the frontend render
 * pass of {@code renderpearl}, shared by OpenGL and Vulkan. Each method
 * delegates straight to the backend, so nothing is counted twice.
 * Measurement only.
 */
@Mixin(FrontendRenderPass.class)
abstract class FrontendRenderPassMixin {
	@Inject(method = "drawIndexed", at = @At("HEAD"))
	private void bloom$drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance, CallbackInfo ci) {
		RenderStats.drawCall(1);
	}

	@Inject(method = "multiDrawIndexed(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"))
	private void bloom$multiDrawIndexed(IntBuffer parameters, int instanceCount, int firstInstance, int drawCount, CallbackInfo ci) {
		RenderStats.drawCall(drawCount);
	}

	@Inject(method = "multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V", at = @At("HEAD"))
	private void bloom$multiDrawIndexedSeparate(PointerBuffer offsets, IntBuffer counts, IntBuffer vertexOffsets, int drawCount, CallbackInfo ci) {
		RenderStats.drawCall(drawCount);
	}

	@Inject(method = "drawIndexedIndirect", at = @At("HEAD"))
	private void bloom$drawIndexedIndirect(GpuBufferSlice commands, int drawCount, CallbackInfo ci) {
		RenderStats.drawCall(drawCount);
	}

	@Inject(method = "drawMultipleIndexed", at = @At("HEAD"))
	private void bloom$drawMultipleIndexed(Collection<?> draws, GpuBuffer indexBuffer, IndexType indexType, Collection<String> dynamicUniforms,
			Object uniformArgument, CallbackInfo ci) {
		// One backend draw per element.
		RenderStats.drawCall(draws.size());
	}

	@Inject(method = "draw", at = @At("HEAD"))
	private void bloom$draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance, CallbackInfo ci) {
		RenderStats.drawCall(1);
	}

	@Inject(method = "multiDraw(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"))
	private void bloom$multiDraw(IntBuffer parameters, int instanceCount, int firstInstance, int drawCount, CallbackInfo ci) {
		RenderStats.drawCall(drawCount);
	}

	@Inject(method = "multiDraw(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V", at = @At("HEAD"))
	private void bloom$multiDrawSeparate(IntBuffer firstVertices, IntBuffer counts, int drawCount, CallbackInfo ci) {
		RenderStats.drawCall(drawCount);
	}

	@Inject(method = "drawIndirect", at = @At("HEAD"))
	private void bloom$drawIndirect(GpuBufferSlice commands, int drawCount, CallbackInfo ci) {
		RenderStats.drawCall(drawCount);
	}
}
