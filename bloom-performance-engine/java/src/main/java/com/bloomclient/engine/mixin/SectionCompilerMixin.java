package com.bloomclient.engine.mixin;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.mesh.SectionMesher;
import com.bloomclient.engine.profiling.EngineProfiler;
import com.bloomclient.engine.validation.MeshValidator;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.Map;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the native mesher into vanilla's section compiler.
 *
 * <p>Vanilla keeps running the whole method: fluids, block entities, the
 * visibility graph input and every block the native mesher did not handle
 * go through the original code. {@code @WrapOperation} is used instead of
 * overwrites so injections of other mods on the same calls keep working.
 */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
	@Shadow @Final private boolean ambientOcclusion;
	@Shadow @Final private boolean cutoutLeaves;
	@Shadow @Final private BlockStateModelSet blockModelSet;
	@Shadow @Final private BlockColors blockColors;

	@Shadow
	private BufferBuilder getOrBeginLayer(Map<ChunkSectionLayer, BufferBuilder> startedLayers, SectionBufferBuilderPack buffers, ChunkSectionLayer layer) {
		throw new AssertionError();
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void bloom$tables(CallbackInfo ci) {
		BloomEngine.onCompilerCreated(blockModelSet, blockColors);
	}

	@Inject(method = "compile", at = @At("HEAD"))
	private void bloom$meshNative(SectionPos sectionPos, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack builders,
			CallbackInfoReturnable<SectionCompiler.Results> cir, @Share("start") LocalLongRef start) {
		start.set(System.nanoTime());
		try {
			SectionMesher.current().prepare(sectionPos, region, ambientOcclusion, cutoutLeaves);
		} catch (Throwable t) {
			BloomEngine.fail("preparing section " + sectionPos, t);
		}
	}

	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
	private void bloom$skipNativeBlocks(ModelBlockRenderer renderer, BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level,
			BlockPos pos, BlockState state, BlockStateModel model, long seed, Operation<Void> original) {
		if (!SectionMesher.current().handledNatively(x, y, z)) {
			original.call(renderer, output, x, y, z, level, pos, state, model, seed);
		}
	}

	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/VisGraph;resolve()Lnet/minecraft/client/renderer/chunk/VisibilitySet;"))
	private VisibilitySet bloom$nativeVisibility(VisGraph graph, Operation<VisibilitySet> original) {
		SectionMesher mesher = SectionMesher.current();
		return mesher.active() ? mesher.visibility() : original.call(graph);
	}

	@Inject(method = "compile", at = @At(value = "INVOKE", target = "Ljava/util/Map;entrySet()Ljava/util/Set;"))
	private void bloom$appendNative(SectionPos sectionPos, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack builders,
			CallbackInfoReturnable<SectionCompiler.Results> cir, @Local(ordinal = 0) Map<ChunkSectionLayer, BufferBuilder> startedLayers) {
		SectionMesher mesher = SectionMesher.current();
		if (mesher.active()) {
			try {
				mesher.appendTo(layer -> getOrBeginLayer(startedLayers, builders, layer));
			} catch (Throwable t) {
				// The builders may hold partial data: let vanilla crash-report it
				// rather than upload a mesh nobody can vouch for.
				BloomEngine.fail("appending native vertices for " + sectionPos, t);
				throw t;
			}
		}
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void bloom$finish(SectionPos sectionPos, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack builders,
			CallbackInfoReturnable<SectionCompiler.Results> cir, @Share("start") LocalLongRef start) {
		SectionMesher mesher = SectionMesher.current();
		if (mesher.bypass()) {
			return;
		}
		boolean nativePath = mesher.active();
		EngineProfiler.recordCompile(nativePath, System.nanoTime() - start.get());
		mesher.finish();
		if (nativePath) {
			MeshValidator.maybeValidate((SectionCompiler) (Object) this, sectionPos, region, sorting, cir.getReturnValue());
		}
	}
}
