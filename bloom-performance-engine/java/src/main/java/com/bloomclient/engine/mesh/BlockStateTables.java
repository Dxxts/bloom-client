package com.bloomclient.engine.mesh;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.bridge.NativeBridge;
import com.bloomclient.engine.bridge.NativeLibrary;
import com.bloomclient.engine.bridge.Protocol;
import com.bloomclient.engine.compatibility.BloomRenderAdapters;
import com.bloomclient.engine.compatibility.CompatibilityReport;
import com.bloomclient.engine.compatibility.FabricApiAdapter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.EmptyFluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-state data shared with the native mesher, plus the Java-side decision
 * of which states may be meshed natively.
 *
 * <p>A state is meshed natively only if every property the mesher reads is
 * provably a function of the state alone, using vanilla code Bloom has
 * ported. Everything else stays on the vanilla path ({@link Fallback}).
 *
 * <p>Fluids are classified separately: a fluid is meshed natively only if its
 * class is one of vanilla's water and lava classes (whose {@code isSame},
 * {@code getOwnHeight} and {@code getFlow} Bloom has checked against the
 * 26.3 sources), and its own height is {@code amount / 9} for that state.
 */
public final class BlockStateTables {
	/** Why a state stays on the Java renderer. {@code NATIVE} means it does not. */
	public enum Fallback {
		NATIVE,
		NOT_A_MODEL,
		CUSTOM_SKIP_RENDERING,
		POSITION_DEPENDENT_SHADE,
		TOO_MANY_TINTS,
		UNVERIFIED_MODEL_CLASS,
		/** The compatibility layer sends this mod's blocks to the Java renderer. */
		FORCED_BY_COMPATIBILITY,
		/** Tints from a Fabric API {@code BlockColorRegistry} factory, computed in Java code. */
		CUSTOM_TINT_FACTORY
	}

	/** Above this many distinct partial face shapes the pair table is not built. */
	private static final int MAX_SHAPES = 512;
	private static final int MAX_FLUID_MODELS = 254;
	private static final Set<Class<?>> VERIFIED_FLUIDS = Set.of(WaterFluid.Source.class, WaterFluid.Flowing.class, LavaFluid.Source.class,
			LavaFluid.Flowing.class);

	private final int epoch;
	private final BlockStateModelSet models;
	private final FluidStateModelSet fluids;
	private final BlockColors colors;
	private final byte[] fallback;
	private final boolean[] nativeFluid;
	private final int nativeFluidStates;
	private final byte[] tintCount;
	private final ModelRegistry registry;
	private final EnumMap<Fallback, Integer> fallbackCounts;
	private final long buildNanos;
	private final int shapeCount;
	private final Map<String, CompatibilityReport.States> namespaces;

	private BlockStateTables(int epoch, BlockStateModelSet models, FluidStateModelSet fluids, BlockColors colors, byte[] fallback, boolean[] nativeFluid,
			byte[] tintCount, EnumMap<Fallback, Integer> fallbackCounts, long buildNanos, int shapeCount, Map<String, CompatibilityReport.States> namespaces) {
		this.namespaces = namespaces;
		this.epoch = epoch;
		this.models = models;
		this.fluids = fluids;
		this.colors = colors;
		this.fallback = fallback;
		this.nativeFluid = nativeFluid;
		int n = 0;
		for (boolean b : nativeFluid) {
			if (b) n++;
		}
		this.nativeFluidStates = n;
		this.tintCount = tintCount;
		this.fallbackCounts = fallbackCounts;
		this.buildNanos = buildNanos;
		this.shapeCount = shapeCount;
		this.registry = new ModelRegistry(epoch);
	}

	public int epoch() {
		return epoch;
	}

	public boolean isFor(BlockStateModelSet models, FluidStateModelSet fluids, BlockColors colors) {
		return this.models == models && this.fluids == fluids && this.colors == colors;
	}

	public FluidStateModelSet fluids() {
		return fluids;
	}

	/** The state holds a fluid of a verified vanilla class. */
	public boolean isNativeFluid(int stateId) {
		return stateId >= 0 && stateId < nativeFluid.length && nativeFluid[stateId];
	}

	public int nativeFluidStates() {
		return nativeFluidStates;
	}

	/** Native and fallback state counts per block namespace (usually the mod id). */
	public Map<String, CompatibilityReport.States> namespaces() {
		return namespaces;
	}

	public BlockStateModelSet models() {
		return models;
	}

	public BlockColors colors() {
		return colors;
	}

	public ModelRegistry registry() {
		return registry;
	}

	public boolean isNative(int stateId) {
		return stateId >= 0 && stateId < fallback.length && fallback[stateId] == 0;
	}

	public int tintCount(int stateId) {
		return tintCount[stateId];
	}

	public Map<Fallback, Integer> fallbackCounts() {
		return fallbackCounts;
	}

	public long buildNanos() {
		return buildNanos;
	}

	public int shapeCount() {
		return shapeCount;
	}

	public int stateCount() {
		return fallback.length;
	}

	public static BlockStateTables build(BlockStateModelSet models, FluidStateModelSet fluids, BlockColors colors, int epoch, Set<String> fallbackNamespaces) {
		long start = System.nanoTime();
		int count = Block.BLOCK_STATE_REGISTRY.size();
		Map<VoxelShape, Integer> shapeIds = new IdentityHashMap<>();
		List<VoxelShape> shapes = new ArrayList<>();
		List<Boolean> covering = new ArrayList<>();
		Map<Block, Integer> blockIds = new IdentityHashMap<>();
		Map<Block, String> blockNamespaces = new IdentityHashMap<>();
		Map<String, CompatibilityReport.States> namespaces = new java.util.TreeMap<>();
		Map<Class<?>, Integer> skipRules = new HashMap<>();
		Map<Class<?>, Boolean> trustedShade = new HashMap<>();
		byte[] fallback = new byte[count];
		boolean[] nativeFluid = new boolean[count];
		Map<Fluid, Integer> fluidGroups = new IdentityHashMap<>();
		Map<FluidModel, Integer> fluidModelIds = new IdentityHashMap<>();
		List<FluidModel> fluidModels = new ArrayList<>();
		byte[] tints = new byte[count];
		EnumMap<Fallback, Integer> counts = new EnumMap<>(Fallback.class);

		int recordsSize = Protocol.TABLE_HEADER_SIZE + count * Protocol.STATE_RECORD_SIZE;
		long table = MemoryUtil.nmemCalloc(1, recordsSize + 8 + (long) MAX_FLUID_MODELS * Protocol.FLUID_MODEL_RECORD_SIZE);
		long pairs = 0;
		try {
			MemoryUtil.memPutInt(table, Protocol.STATE_TABLE_MAGIC);
			MemoryUtil.memPutInt(table + 4, Protocol.VERSION);
			MemoryUtil.memPutInt(table + 8, count);
			MemoryUtil.memPutInt(table + 12, epoch);
			for (int id = 0; id < count; id++) {
				BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
				long o = table + Protocol.TABLE_HEADER_SIZE + (long) id * Protocol.STATE_RECORD_SIZE;
				if (state == null) {
					MemoryUtil.memPutInt(o, Protocol.STATE_DYNAMIC);
					fallback[id] = (byte) Fallback.NOT_A_MODEL.ordinal();
					continue;
				}
				Block block = state.getBlock();
				boolean dynamic = block.hasDynamicShape() || !trustedShade.computeIfAbsent(block.getClass(), BlockStateTables::shadeIsStateOnly);
				int flags = 0;
				if (state.isAir()) flags |= Protocol.STATE_AIR;
				if (state.isSolidRender()) flags |= Protocol.STATE_SOLID_RENDER;
				if (state.isLightPermeable()) flags |= Protocol.STATE_LIGHT_PERMEABLE;
				if (state.emissiveRendering()) flags |= Protocol.STATE_EMISSIVE_RENDERING;
				if (dynamic) flags |= Protocol.STATE_DYNAMIC;
				if (block instanceof LeavesBlock) flags |= Protocol.STATE_LEAVES;
				if (state.isSolid()) flags |= Protocol.STATE_SOLID;
				if (FabricApiAdapter.overlayTransparent(block) || block instanceof LeavesBlock) flags |= Protocol.STATE_OVERLAY_NEIGHBOR;
				float shade = 1.0F;
				if (!dynamic) {
					if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) flags |= Protocol.STATE_COLLISION_FULL_BLOCK;
					shade = state.getShadeBrightness(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
				}
				int tintSources = colors.getTintSources(state).size();
				if (tintSources > 0) flags |= Protocol.STATE_TINTED;
				tints[id] = (byte) Math.min(tintSources, 127);
				int skip = skipRules.computeIfAbsent(block.getClass(), BlockStateTables::skipRule);

				int faceClasses = 0;
				for (Direction d : Direction.values()) {
					VoxelShape shape = state.getFaceOcclusionShape(d);
					int cls;
					int shapeId = Protocol.NO_SHAPE;
					if (shape == Shapes.empty()) {
						cls = Protocol.FACE_EMPTY;
					} else if (shape == Shapes.block()) {
						cls = Protocol.FACE_BLOCK;
					} else {
						// One coverage test per distinct shape instance, not per state face.
						shapeId = shapeIds.computeIfAbsent(shape, s -> {
							shapes.add(s);
							covering.add(!Shapes.joinIsNotEmpty(Shapes.block(), s, BooleanOp.ONLY_FIRST));
							return shapes.size() - 1;
						});
						cls = covering.get(shapeId) ? Protocol.FACE_PARTIAL_COVERING : Protocol.FACE_PARTIAL;
						if (shapeId >= Protocol.NO_SHAPE) shapeId = Protocol.NO_SHAPE;
					}
					faceClasses |= cls << (d.ordinal() * 2);
					MemoryUtil.memPutShort(o + 16 + d.ordinal() * 2L, (short) shapeId);
				}
				MemoryUtil.memPutInt(o, flags);
				MemoryUtil.memPutInt(o + 4, blockIds.computeIfAbsent(block, b -> blockIds.size()));
				MemoryUtil.memPutFloat(o + 8, shade);
				MemoryUtil.memPutByte(o + 12, (byte) state.getLightEmission());
				MemoryUtil.memPutByte(o + 13, (byte) (skip < 0 ? 0xFF : skip));
				MemoryUtil.memPutShort(o + 14, (short) faceClasses);
				int group = fluidGroup(state.getFluidState(), fluidGroups);
				if (group != Protocol.FLUID_GROUP_NONE && group != Protocol.FLUID_GROUP_UNVERIFIED) {
					FluidModel model = fluids.get(state.getFluidState());
					Integer modelId = fluidModelIds.get(model);
					if (modelId == null && fluidModels.size() < MAX_FLUID_MODELS) {
						modelId = fluidModels.size();
						fluidModelIds.put(model, modelId);
						fluidModels.add(model);
					}
					if (modelId == null) {
						group = Protocol.FLUID_GROUP_UNVERIFIED;
					} else {
						MemoryUtil.memPutByte(o + 29, (byte) (int) modelId);
						MemoryUtil.memPutByte(o + 30, (byte) state.getFluidState().getAmount());
						nativeFluid[id] = true;
					}
				}
				MemoryUtil.memPutByte(o + 28, (byte) group);

				Fallback why;
				if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
					why = Fallback.NOT_A_MODEL;
				} else if (skip < 0) {
					why = Fallback.CUSTOM_SKIP_RENDERING;
				} else if (dynamic) {
					why = Fallback.POSITION_DEPENDENT_SHADE;
				} else if (tintSources > Protocol.MAX_TINTS) {
					why = Fallback.TOO_MANY_TINTS;
				} else if (tintSources == 0 && FabricApiAdapter.hasTintFactory(state)) {
					why = Fallback.CUSTOM_TINT_FACTORY;
				} else if (!BloomRenderAdapters.isStaticModel(models.get(state))) {
					why = Fallback.UNVERIFIED_MODEL_CLASS;
				} else {
					why = Fallback.NATIVE;
				}
				String namespace = blockNamespaces.computeIfAbsent(block, b -> BuiltInRegistries.BLOCK.getKey(b).getNamespace());
				if (why == Fallback.NATIVE && fallbackNamespaces.contains(namespace)) {
					why = Fallback.FORCED_BY_COMPATIBILITY;
				}
				CompatibilityReport.States ns = namespaces.computeIfAbsent(namespace, n -> new CompatibilityReport.States());
				if (why == Fallback.NATIVE) {
					if (BloomRenderAdapters.isBuiltInModel(models.get(state))) ns.automatic++;
					else ns.adapter++;
				} else if (why != Fallback.NOT_A_MODEL) {
					ns.fallback.merge(why.name(), 1, Integer::sum);
				}
				fallback[id] = (byte) why.ordinal();
				counts.merge(why, 1, Integer::sum);
			}

			int pairSize = 0;
			if (!shapes.isEmpty() && shapes.size() <= MAX_SHAPES) {
				int p = shapes.size();
				pairSize = Protocol.TABLE_HEADER_SIZE + (p * p + 7) / 8;
				pairs = MemoryUtil.nmemCalloc(1, pairSize);
				MemoryUtil.memPutInt(pairs, Protocol.SHAPE_PAIRS_MAGIC);
				MemoryUtil.memPutInt(pairs + 4, Protocol.VERSION);
				MemoryUtil.memPutInt(pairs + 8, p);
				MemoryUtil.memPutInt(pairs + 12, epoch);
				for (int a = 0; a < p; a++) {
					for (int b = 0; b < p; b++) {
						if (Shapes.joinIsNotEmpty(shapes.get(a), shapes.get(b), BooleanOp.ONLY_FIRST)) {
							int bit = a * p + b;
							long addr = pairs + Protocol.TABLE_HEADER_SIZE + bit / 8;
							MemoryUtil.memPutByte(addr, (byte) (MemoryUtil.memGetByte(addr) | 1 << (bit % 8)));
						}
					}
				}
			}
			int tableSize = recordsSize + writeFluidModels(table + recordsSize, fluidModels);
			int status = NativeBridge.installTables(table, tableSize, pairs, pairSize);
			if (status != Protocol.STATUS_OK) {
				throw new IllegalStateException("installTables returned " + status + ": " + NativeLibrary.lastError());
			}
		} finally {
			MemoryUtil.nmemFree(table);
			if (pairs != 0) MemoryUtil.nmemFree(pairs);
		}
		long elapsed = System.nanoTime() - start;
		BloomEngine.LOGGER.info("Bloom Engine: tables for {} states built in {} ms ({} partial face shapes, {} fluid models); per state: {}",
				count, elapsed / 1_000_000, shapes.size(), fluidModels.size(), counts);
		return new BlockStateTables(epoch, models, fluids, colors, fallback, nativeFluid, tints, counts, elapsed, shapes.size(), namespaces);
	}

	/**
	 * Fluid group of a state: 0 for no fluid, a shared id for fluids that are
	 * the same per {@code Fluid.isSame}, {@link Protocol#FLUID_GROUP_UNVERIFIED}
	 * for anything Bloom has not verified.
	 */
	private static int fluidGroup(FluidState fluidState, Map<Fluid, Integer> groups) {
		Fluid fluid = fluidState.getType();
		if (fluid.getClass() == EmptyFluid.class) {
			return Protocol.FLUID_GROUP_NONE;
		}
		if (!VERIFIED_FLUIDS.contains(fluid.getClass()) || FabricApiAdapter.fluidOverridden(fluid)) {
			return Protocol.FLUID_GROUP_UNVERIFIED;
		}
		// The native port computes the own height as amount / 9: check it for this state.
		int amount = fluidState.getAmount();
		if (amount < 0 || amount > 255 || fluidState.getOwnHeight() != (float) amount / 9.0F) {
			return Protocol.FLUID_GROUP_UNVERIFIED;
		}
		// Water and flowing water are the same fluid (and so are the two lavas).
		Fluid source = ((FlowingFluid) fluid).getSource();
		Integer group = groups.get(source);
		if (group == null) {
			if (groups.size() >= Protocol.FLUID_GROUP_UNVERIFIED - 1) {
				return Protocol.FLUID_GROUP_UNVERIFIED;
			}
			group = groups.size() + 1;
			groups.put(source, group);
		}
		return group;
	}

	/** Writes the fluid model section after the state records; returns its size. */
	private static int writeFluidModels(long at, List<FluidModel> models) {
		MemoryUtil.memPutInt(at, Protocol.FLUID_MODELS_MAGIC);
		MemoryUtil.memPutInt(at + 4, models.size());
		for (int i = 0; i < models.size(); i++) {
			FluidModel model = models.get(i);
			long o = at + 8 + (long) i * Protocol.FLUID_MODEL_RECORD_SIZE;
			int flags = 0;
			if (model.tintSource() != null) flags |= Protocol.FLUID_MODEL_TINTED;
			if (model.overlayMaterial() != null) flags |= Protocol.FLUID_MODEL_HAS_OVERLAY;
			MemoryUtil.memPutByte(o, (byte) model.layer().ordinal());
			MemoryUtil.memPutByte(o + 1, (byte) flags);
			putSprite(o + 4, model.stillMaterial().sprite());
			putSprite(o + 20, model.flowingMaterial().sprite());
			if (model.overlayMaterial() != null) putSprite(o + 36, model.overlayMaterial().sprite());
		}
		return 8 + models.size() * Protocol.FLUID_MODEL_RECORD_SIZE;
	}

	private static void putSprite(long o, TextureAtlasSprite sprite) {
		MemoryUtil.memPutFloat(o, sprite.getU0());
		MemoryUtil.memPutFloat(o + 4, sprite.getU1());
		MemoryUtil.memPutFloat(o + 8, sprite.getV0());
		MemoryUtil.memPutFloat(o + 12, sprite.getV1());
	}

	/** Skip rule of {@code Block.skipRendering}, or -1 if it is custom code. */
	private static int skipRule(Class<?> blockClass) {
		Class<?> owner = declaringClass(blockClass, "skipRendering", BlockState.class, BlockState.class, Direction.class);
		if (owner == BlockBehaviour.class) return Protocol.SKIP_NONE;
		if (owner == HalfTransparentBlock.class) return Protocol.SKIP_SAME_BLOCK;
		if (owner == LeavesBlock.class) return Protocol.SKIP_LEAVES;
		return -1;
	}

	/**
	 * Vanilla's overrides of {@code getShadeBrightness} only read the state
	 * (checked against the 26.3 sources). Overrides from mods are not trusted.
	 */
	private static boolean shadeIsStateOnly(Class<?> blockClass) {
		Class<?> owner = declaringClass(blockClass, "getShadeBrightness", BlockState.class, BlockGetter.class, BlockPos.class);
		return owner != null && owner.getName().startsWith("net.minecraft.");
	}

	private static Class<?> declaringClass(Class<?> type, String name, Class<?>... params) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			try {
				Method m = c.getDeclaredMethod(name, params);
				return m.getDeclaringClass();
			} catch (NoSuchMethodException ignored) {
				// keep walking up
			}
		}
		return null;
	}
}
