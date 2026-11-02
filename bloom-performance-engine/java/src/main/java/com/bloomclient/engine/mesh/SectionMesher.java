package com.bloomclient.engine.mesh;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.bridge.NativeBridge;
import com.bloomclient.engine.bridge.Protocol;
import com.bloomclient.engine.compatibility.FabricApiAdapter;
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
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Native meshing of one section, owned by one worker thread.
 *
 * <p>{@link #prepare} snapshots the section and runs the native mesher once.
 * During the vanilla loop that follows, {@link #handledNatively} and
 * {@link #fluidHandledNatively} tell the compiler which block models and
 * fluids to skip, and {@link #appendTo} copies the native vertices into
 * vanilla's buffers before they are built.
 */
public final class SectionMesher {
	private static final ThreadLocal<SectionMesher> CURRENT = ThreadLocal.withInitial(SectionMesher::new);
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();

	private long context;
	private final long snapshot = MemoryUtil.nmemAlloc(Protocol.SNAPSHOT_MAX_SIZE);
	private final long result = MemoryUtil.nmemAlloc(Protocol.RESULT_SIZE);
	private final long plan = MemoryUtil.nmemAlloc(Protocol.PLAN_SIZE);
	private final BlockState[] center = new BlockState[4096];
	private final boolean[] handled = new boolean[4096];
	private final boolean[] handledFluid = new boolean[4096];
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
		boolean fluids = BloomEngine.config().nativeFluids;
		int size = writeSnapshot(t, sectionPos, region, ambientOcclusion, cutoutLeaves, fluids);
		// Light is read only where the mesher needs it (blocks with a visible face).
		int status = NativeBridge.planLight(context, snapshot, size, plan);
		if (status == Protocol.STATUS_OK) {
			writeLight(sectionPos, region);
			if (fluids) {
				size = writeFluids(t, sectionPos, region, size);
			}
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
			int entry = MemoryUtil.memGetShort(demotedPtr + i * 2L) & 0xFFFF;
			if ((entry & Protocol.DEMOTED_FLUID_BIT) != 0) {
				handledFluid[entry & 0xFFF] = false;
			} else {
				handled[entry] = false;
			}
		}
		active = true;
		EngineProfiler.recordNativeSection(snapshotDone - started, meshDone - snapshotDone,
				MemoryUtil.memGetInt(result + 72), MemoryUtil.memGetInt(result + 84), MemoryUtil.memGetInt(result + 96),
				MemoryUtil.memGetInt(result + 100));
		return true;
	}

	private int writeSnapshot(BlockStateTables t, SectionPos sectionPos, RenderSectionRegion region, boolean ao, boolean cutoutLeaves,
			boolean fluids) {
		long s = snapshot;
		int ox = sectionPos.minBlockX();
		int oy = sectionPos.minBlockY();
		int oz = sectionPos.minBlockZ();
		MemoryUtil.memPutInt(s, Protocol.SNAPSHOT_MAGIC);
		MemoryUtil.memPutShort(s + 4, (short) Protocol.VERSION);
		MemoryUtil.memPutShort(s + 6, (short) ((ao ? Protocol.FLAG_AMBIENT_OCCLUSION : 0) | (cutoutLeaves ? Protocol.FLAG_CUTOUT_LEAVES : 0)
				| (fluids ? Protocol.FLAG_NATIVE_FLUIDS : 0)));
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
		// Fluid entries are added after the plan (writeFluids).
		MemoryUtil.memPutInt(s + Protocol.SNAPSHOT_FLUID_COUNT_OFFSET, 0);

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
			handledFluid[index] = fluids && t.isNativeFluid(id);
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
						// The entry carries the origin, in the arithmetic of the renderer Bloom matches.
						Vec3 offset = state.getOffset(pos);
						long e = offsetScratch + (long) offsetCount * Protocol.OFFSET_ENTRY_SIZE;
						MemoryUtil.memPutShort(e, (short) index);
						MemoryUtil.memPutFloat(e + 4, FabricApiAdapter.origin((float) x, offset.x));
						MemoryUtil.memPutFloat(e + 8, FabricApiAdapter.origin((float) y, offset.y));
						MemoryUtil.memPutFloat(e + 12, FabricApiAdapter.origin((float) z, offset.z));
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

	/**
	 * Appends the fluid entries the plan asked for: the tint, and the flow
	 * terms of {@code FluidRenderer} for fluids whose top face is drawn.
	 * Returns the new snapshot size.
	 */
	private int writeFluids(BlockStateTables t, SectionPos sectionPos, RenderSectionRegion region, int size) {
		int requests = MemoryUtil.memGetInt(plan + Protocol.PLAN_FLUID_COUNT_OFFSET);
		int ox = sectionPos.minBlockX();
		int oy = sectionPos.minBlockY();
		int oz = sectionPos.minBlockZ();
		for (int i = 0; i < requests; i++) {
			int request = MemoryUtil.memGetShort(plan + Protocol.PLAN_FLUIDS_OFFSET + i * 2L) & 0xFFFF;
			int index = request & 0xFFF;
			BlockState state = center[index];
			FluidState fluid = state.getFluidState();
			pos.set(ox + (index & 15), oy + (index >> 8), oz + (index >> 4 & 15));
			FluidModel model = t.fluids().get(fluid);
			int tint = model.tintSource() != null ? model.tintSource().colorInWorld(state, region, pos) : -1;
			int flags = 0;
			float s = 0.0F;
			float c = 0.0F;
			if ((request & Protocol.PLAN_FLUID_NEEDS_FLOW) != 0) {
				flags |= Protocol.FLUID_ENTRY_FLOW_KNOWN;
				Vec3 flow = fluid.getFlow(region, pos);
				if (!(flow.x == 0.0 && flow.z == 0.0)) {
					// Same expressions as FluidRenderer.tesselate.
					float angle = (float) Mth.atan2(flow.z, flow.x) - ((float) Math.PI / 2F);
					s = Mth.sin((double) angle) * 0.25F;
					c = Mth.cos((double) angle) * 0.25F;
					flags |= Protocol.FLUID_ENTRY_HAS_FLOW;
				}
			}
			long e = snapshot + size + (long) i * Protocol.FLUID_ENTRY_SIZE;
			MemoryUtil.memPutShort(e, (short) index);
			MemoryUtil.memPutByte(e + 2, (byte) flags);
			MemoryUtil.memPutByte(e + 3, (byte) 0);
			MemoryUtil.memPutInt(e + 4, tint);
			MemoryUtil.memPutFloat(e + 8, s);
			MemoryUtil.memPutFloat(e + 12, c);
		}
		MemoryUtil.memPutInt(snapshot + Protocol.SNAPSHOT_FLUID_COUNT_OFFSET, requests);
		return size + requests * Protocol.FLUID_ENTRY_SIZE;
	}

	/**
	 * Whether any light value the last native mesh read has changed since.
	 * Light comes from the live light engine, not from the section copy, so
	 * a light update between the native mesh and a later vanilla compile of
	 * the same section makes them differ legitimately.
	 */
	public boolean lightChanged(SectionPos sectionPos, RenderSectionRegion region) {
		int ox = sectionPos.minBlockX();
		int oy = sectionPos.minBlockY();
		int oz = sectionPos.minBlockZ();
		long lightBase = snapshot + Protocol.SNAPSHOT_LIGHT_OFFSET;
		int dim = Protocol.LIGHT_DIM;
		int total = dim * dim * dim;
		for (int byteIndex = 0; byteIndex < Protocol.LIGHT_MASK_BYTES; byteIndex++) {
			int bits = MemoryUtil.memGetByte(plan + byteIndex) & 0xFF;
			while (bits != 0) {
				int i = byteIndex * 8 + Integer.numberOfTrailingZeros(bits);
				bits &= bits - 1;
				if (i >= total) {
					break;
				}
				pos.set(ox + i % dim + Protocol.LIGHT_MIN, oy + i / (dim * dim) + Protocol.LIGHT_MIN, oz + i / dim % dim + Protocol.LIGHT_MIN);
				int now = region.getBrightness(LightLayer.SKY, pos) << 4 | region.getBrightness(LightLayer.BLOCK, pos);
				if ((MemoryUtil.memGetByte(lightBase + i) & 0xFF) != now) {
					return true;
				}
			}
		}
		return false;
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
			int bits = MemoryUtil.memGetByte(plan + byteIndex) & 0xFF;
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

	/** Whether the fluid at {@code pos} (any position inside the section) was meshed natively. */
	public boolean fluidHandledNatively(BlockPos pos) {
		return active && handledFluid[(pos.getX() & 15) | (pos.getZ() & 15) << 4 | (pos.getY() & 15) << 8];
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
