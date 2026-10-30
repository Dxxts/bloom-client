package com.bloomclient.engine.mesh;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.bridge.NativeBridge;
import com.bloomclient.engine.bridge.Protocol;
import com.bloomclient.engine.access.BloomBlockState;
import com.bloomclient.engine.access.BloomBufferBuilder;
import com.bloomclient.engine.mixin.VisibilitySetAccessor;
import com.bloomclient.engine.profiling.EngineProfiler;
import com.mojang.blaze3d.vertex.BufferBuilder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Native meshing of one section, owned by one worker thread.
 *
 * <p>{@link #prepare} snapshots the section and runs the native mesher once.
 * During the vanilla loop that follows, {@link #handledNatively} tells the
 * compiler which blocks to skip, and {@link #appendTo} copies the native
 * vertices into vanilla's buffers before they are built.
 */
public final class SectionMesher {
	private static final ThreadLocal<SectionMesher> CURRENT = ThreadLocal.withInitial(SectionMesher::new);
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();

	private long context;
	private final long snapshot = MemoryUtil.nmemAlloc(Protocol.SNAPSHOT_MAX_SIZE);
	private final long result = MemoryUtil.nmemAlloc(Protocol.RESULT_SIZE);
	private final long lightMask = MemoryUtil.nmemAlloc(Protocol.LIGHT_MASK_BYTES);
	private final BlockState[] center = new BlockState[4096];
	private final boolean[] handled = new boolean[4096];
	private final List<BlockStateModelPart> parts = new ArrayList<>(4);
	private final IdentityHashMap<BlockStateModelPart, Integer> singlePartIds = new IdentityHashMap<>();
	private final RandomSource random = RandomSource.createThreadLocalInstance(0L);
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private @Nullable BlockStateTables tables;
	private boolean active;
	/** Set while the validator compiles the same section with the vanilla renderer. */
	private boolean bypass;

	public static SectionMesher current() {
		return CURRENT.get();
	}

	public boolean active() {
		return active;
	}

	public void setBypass(boolean bypass) {
		this.bypass = bypass;
	}

	public boolean bypass() {
		return bypass;
	}

	/** Runs the native mesher. Returns {@code false} if vanilla must do everything. */
	public boolean prepare(SectionPos sectionPos, RenderSectionRegion region, boolean ambientOcclusion, boolean cutoutLeaves) {
		active = false;
		BlockStateTables t = BloomEngine.tables();
		if (bypass || t == null || !BloomEngine.active()) {
			releaseIfDisabled();
			return false;
		}
		if (t != tables) {
			tables = t;
			singlePartIds.clear();
		}
		if (context == 0) {
			context = NativeBridge.createContext();
			if (context == 0) {
				BloomEngine.fail("creating a native context", null);
				return false;
			}
		}
		long started = System.nanoTime();
		int size = writeSnapshot(t, sectionPos, region, ambientOcclusion, cutoutLeaves);
		// Light is read only where the mesher needs it (blocks with a visible face).
		int status = NativeBridge.planLight(context, snapshot, size, lightMask);
		if (status == Protocol.STATUS_OK) {
			writeLight(sectionPos, region);
		}
		long snapshotDone = System.nanoTime();
		if (status == Protocol.STATUS_OK) {
			status = NativeBridge.meshSection(context, snapshot, size, result);
		}
		long meshDone = System.nanoTime();
		if (status == Protocol.STATUS_STALE_TABLES) {
			return false;
		}
		if (status != Protocol.STATUS_OK) {
			BloomEngine.fail("meshing section " + sectionPos + " (status " + status + ")", null);
			return false;
		}
		int demoted = MemoryUtil.memGetInt(result + 4);
		long demotedPtr = MemoryUtil.memGetLong(result + 8);
		for (int i = 0; i < demoted; i++) {
			handled[MemoryUtil.memGetShort(demotedPtr + i * 2L) & 0xFFFF] = false;
		}
		active = true;
		EngineProfiler.recordNativeSection(snapshotDone - started, meshDone - snapshotDone,
				MemoryUtil.memGetInt(result + 72), demoted);
		return true;
	}

	private int writeSnapshot(BlockStateTables t, SectionPos sectionPos, RenderSectionRegion region, boolean ao, boolean cutoutLeaves) {
		long s = snapshot;
		int ox = sectionPos.minBlockX();
		int oy = sectionPos.minBlockY();
		int oz = sectionPos.minBlockZ();
		MemoryUtil.memPutInt(s, Protocol.SNAPSHOT_MAGIC);
		MemoryUtil.memPutShort(s + 4, (short) Protocol.VERSION);
		MemoryUtil.memPutShort(s + 6, (short) ((ao ? Protocol.FLAG_AMBIENT_OCCLUSION : 0) | (cutoutLeaves ? Protocol.FLAG_CUTOUT_LEAVES : 0)));
		MemoryUtil.memPutInt(s + 8, t.epoch());
		MemoryUtil.memPutInt(s + 20, sectionPos.x());
		MemoryUtil.memPutInt(s + 24, sectionPos.y());
		MemoryUtil.memPutInt(s + 28, sectionPos.z());
		CardinalLighting light = region.cardinalLighting();
		MemoryUtil.memPutFloat(s + 32, light.down());
		MemoryUtil.memPutFloat(s + 36, light.up());
		MemoryUtil.memPutFloat(s + 40, light.north());
		MemoryUtil.memPutFloat(s + 44, light.south());
		MemoryUtil.memPutFloat(s + 48, light.west());
		MemoryUtil.memPutFloat(s + 52, light.east());

		long states = s + Protocol.SNAPSHOT_STATES_OFFSET;
		int i = 0;
		for (int y = Protocol.STATE_MIN; y < Protocol.STATE_MIN + Protocol.STATE_DIM; y++) {
			for (int z = Protocol.STATE_MIN; z < Protocol.STATE_MIN + Protocol.STATE_DIM; z++) {
				for (int x = Protocol.STATE_MIN; x < Protocol.STATE_MIN + Protocol.STATE_DIM; x++, i++) {
					BlockState state = region.getBlockState(pos.set(ox + x, oy + y, oz + z));
					MemoryUtil.memPutInt(states + i * 4L, ((BloomBlockState) state).bloom$stateId());
					if ((x | y | z) >= 0 && x < 16 && y < 16 && z < 16) {
						center[x | z << 4 | y << 8] = state;
					}
				}
			}
		}

		long models = s + Protocol.SNAPSHOT_MODELS_OFFSET;
		long tables = s + Protocol.SNAPSHOT_TABLES_OFFSET;
		int tintCount = 0;
		// Offsets are written after the tints, so collect them first.
		int offsetCount = 0;
		long offsetScratch = tables + 4096L * Protocol.TINT_ENTRY_SIZE;
		ModelRegistry registry = t.registry();
		for (int index = 0; index < 4096; index++) {
			BlockState state = center[index];
			int id = ((BloomBlockState) state).bloom$stateId();
			int model;
			if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
				model = Protocol.MODEL_NONE;
			} else if (!t.isNative(id)) {
				model = Protocol.MODEL_JAVA;
			} else {
				int x = index & 15, z = index >> 4 & 15, y = index >> 8;
				pos.set(ox + x, oy + y, oz + z);
				model = resolveModel(t, registry, state);
				if (model > 0) {
					int tints = t.tintCount(id);
					if (tints > 0) {
						long e = tables + (long) tintCount * Protocol.TINT_ENTRY_SIZE;
						MemoryUtil.memPutShort(e, (short) index);
						MemoryUtil.memPutByte(e + 2, (byte) tints);
						List<BlockTintSource> sources = t.colors().getTintSources(state);
						for (int k = 0; k < Protocol.MAX_TINTS; k++) {
							MemoryUtil.memPutInt(e + 4 + k * 4L, k < tints ? sources.get(k).colorInWorld(state, region, pos) : -1);
						}
						tintCount++;
					}
					if (state.hasOffsetFunction()) {
						Vec3 offset = state.getOffset(pos);
						long e = offsetScratch + (long) offsetCount * Protocol.OFFSET_ENTRY_SIZE;
						MemoryUtil.memPutShort(e, (short) index);
						MemoryUtil.memPutFloat(e + 4, (float) offset.x);
						MemoryUtil.memPutFloat(e + 8, (float) offset.y);
						MemoryUtil.memPutFloat(e + 12, (float) offset.z);
						offsetCount++;
					}
				}
			}
			handled[index] = model > 0;
			MemoryUtil.memPutInt(models + index * 4L, model);
		}
		long offsetsAt = tables + (long) tintCount * Protocol.TINT_ENTRY_SIZE;
		if (offsetCount > 0 && offsetsAt != offsetScratch) {
			MemoryUtil.memCopy(offsetScratch, offsetsAt, (long) offsetCount * Protocol.OFFSET_ENTRY_SIZE);
		}
		MemoryUtil.memPutInt(s + 12, tintCount);
		MemoryUtil.memPutInt(s + 16, offsetCount);
		return Protocol.SNAPSHOT_TABLES_OFFSET + tintCount * Protocol.TINT_ENTRY_SIZE + offsetCount * Protocol.OFFSET_ENTRY_SIZE;
	}

	/** Fills the light bytes selected by the native light plan. */
	private void writeLight(SectionPos sectionPos, RenderSectionRegion region) {
		int ox = sectionPos.minBlockX();
		int oy = sectionPos.minBlockY();
		int oz = sectionPos.minBlockZ();
		long lightBase = snapshot + Protocol.SNAPSHOT_LIGHT_OFFSET;
		int dim = Protocol.LIGHT_DIM;
		int total = dim * dim * dim;
		for (int byteIndex = 0; byteIndex < Protocol.LIGHT_MASK_BYTES; byteIndex++) {
			int bits = MemoryUtil.memGetByte(lightMask + byteIndex) & 0xFF;
			while (bits != 0) {
				int i = byteIndex * 8 + Integer.numberOfTrailingZeros(bits);
				bits &= bits - 1;
				if (i >= total) {
					break;
				}
				int x = i % dim + Protocol.LIGHT_MIN;
				int z = i / dim % dim + Protocol.LIGHT_MIN;
				int y = i / (dim * dim) + Protocol.LIGHT_MIN;
				pos.set(ox + x, oy + y, oz + z);
				int sky = region.getBrightness(LightLayer.SKY, pos);
				int block = region.getBrightness(LightLayer.BLOCK, pos);
				MemoryUtil.memPutByte(lightBase + i, (byte) (sky << 4 | block));
			}
		}
	}

	/** Same part selection as {@code ModelBlockRenderer.tesselateBlock}. */
	private int resolveModel(BlockStateTables t, ModelRegistry registry, BlockState state) {
		BlockStateModel model = t.models().get(state);
		parts.clear();
		if (!(model instanceof SingleVariant)) {
			// SingleVariant ignores the random source; everything else uses it.
			random.setSeed(state.getSeed(pos));
		}
		model.collectParts(random, parts);
		if (parts.size() == 1) {
			BlockStateModelPart part = parts.getFirst();
			Integer cached = singlePartIds.get(part);
			if (cached != null) {
				return cached;
			}
			int id = registry.idFor(parts);
			if (id > 0) {
				singlePartIds.put(part, id);
			}
			return id;
		}
		return registry.idFor(parts);
	}

	/** Whether the block at section-relative {@code (x, y, z)} was meshed natively. */
	public boolean handledNatively(float x, float y, float z) {
		return active && handled[(int) x | (int) z << 4 | (int) y << 8];
	}

	public VisibilitySet visibility() {
		VisibilitySet set = new VisibilitySet();
		BitSet data = ((VisibilitySetAccessor) set).bloom$data();
		data.clear();
		data.or(BitSet.valueOf(new long[] {MemoryUtil.memGetLong(result + 64)}));
		return set;
	}

	/** Copies the native vertices into vanilla's per-layer builders. */
	public void appendTo(Function<ChunkSectionLayer, BufferBuilder> builders) {
		for (int layer = 0; layer < Protocol.LAYER_COUNT; layer++) {
			int vertices = MemoryUtil.memGetInt(result + 24 + layer * 16L);
			if (vertices > 0) {
				long ptr = MemoryUtil.memGetLong(result + 16 + layer * 16L);
				((BloomBufferBuilder) builders.apply(LAYERS[layer])).bloom$appendVertices(ptr, vertices);
			}
		}
	}

	public void finish() {
		active = false;
		java.util.Arrays.fill(center, null);
	}

	/** Frees this thread's native context once the engine is off. */
	private void releaseIfDisabled() {
		if (context != 0 && !BloomEngine.active()) {
			NativeBridge.destroyContext(context);
			context = 0;
			tables = null;
			singlePartIds.clear();
		}
	}
}
