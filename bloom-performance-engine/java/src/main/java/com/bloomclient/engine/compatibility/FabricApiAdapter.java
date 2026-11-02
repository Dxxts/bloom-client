package com.bloomclient.engine.compatibility;

import com.bloomclient.engine.BloomEngine;
import java.lang.reflect.Method;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

/**
 * Native Bloom Adapter for the rendering modules of Fabric API (26.3,
 * analysed on fabric-api 0.165.0). Their terrain mixins do this:
 *
 * <ul>
 *   <li>{@code fabric-renderer-api-v1}: redirects every
 *   {@code ModelBlockRenderer.tesselateBlock} call in
 *   {@code SectionCompiler.compile} to the active renderer (Indigo). Bloom's
 *   {@code @WrapOperation} wraps that redirect, so blocks Bloom meshes skip it
 *   and every other block still goes through Indigo.</li>
 *   <li>{@code fabric-rendering-v1}: blocks without vanilla tint sources can
 *   get tints from {@code BlockColorRegistry} factories. Such states go to
 *   the Java renderer.</li>
 *   <li>{@code fabric-rendering-fluids-v1}: a fluid can have its own render
 *   handler, and the overlay sprite uses {@code isBlockTransparent} instead of
 *   {@code instanceof HalfTransparentBlock}. Fluids with an override go to
 *   Java; the overlay flag follows the registry.</li>
 *   <li>{@code fabric-renderer-indigo}: only an accessor to vanilla's AO
 *   cache.</li>
 * </ul>
 *
 * Whether Bloom's output then equals the Indigo path is not assumed: it is
 * checked by the runtime validator and recorded in the compatibility database.
 * Reflection only: Bloom does not depend on Fabric API.
 */
public final class FabricApiAdapter {
	public static final int VERSION = 1;
	public static final Set<String> MODULES = Set.of("fabric-renderer-api-v1", "fabric-rendering-v1", "fabric-rendering-fluids-v1", "fabric-renderer-indigo");

	private static final @Nullable Method TINT_FACTORY = find("net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry", "getFactory", BlockState.class);
	private static final @Nullable Method FLUID_OVERRIDE = find("net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry", "getOverride",
			Fluid.class);
	private static final @Nullable Method BLOCK_TRANSPARENT = find("net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry",
			"isBlockTransparent", Block.class);

	/** State of Indigo, the default Fabric renderer, which re-tesselates every block Bloom does not mesh. */
	public enum Indigo {
		/** Not installed. */
		ABSENT,
		/** Installed with vanilla lighting: its output is the vanilla one (validated in game). */
		VANILLA_LIGHTING,
		/** Installed with its own AO or lighting fixes (the default): differs from vanilla. */
		CUSTOM_LIGHTING,
		/** Installed but its configuration cannot be read. */
		UNKNOWN
	}

	private static volatile @Nullable Indigo indigo;
	/** Whether Indigo's lighting fixes must be off too (see docs/COMPATIBILITA.md for the validation runs). */
	private static final boolean REQUIRE_NO_FIXES = Boolean.parseBoolean(System.getProperty("bloom.indigo.requireNoFixes", "false"));

	private FabricApiAdapter() {
	}

	public static Indigo indigo() {
		Indigo cached = indigo;
		if (cached != null) return cached;
		Indigo state;
		if (!FabricLoader.getInstance().isModLoaded("fabric-renderer-indigo")) {
			state = Indigo.ABSENT;
		} else {
			try {
				Class<?> c = Class.forName("net.fabricmc.fabric.impl.client.indigo.Indigo");
				boolean vanilla = String.valueOf(c.getField("AMBIENT_OCCLUSION_MODE").get(null)).equals("VANILLA");
				if (REQUIRE_NO_FIXES) {
					for (String fix : new String[] {"FIX_SMOOTH_LIGHTING_OFFSET", "FIX_MEAN_LIGHT_CALCULATION", "FIX_EXTERIOR_VERTEX_LIGHTING", "FIX_LUMINOUS_AO_SHADE"}) {
						vanilla &= !c.getField(fix).getBoolean(null);
					}
				}
				state = vanilla ? Indigo.VANILLA_LIGHTING : Indigo.CUSTOM_LIGHTING;
			} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
				state = Indigo.UNKNOWN;
			}
		}
		indigo = state;
		return state;
	}

	/**
	 * Why the Fabric API adapter cannot be used, or {@code null} if it can.
	 * Indigo renders every block Bloom leaves to Java, so Bloom's blocks must
	 * look like Indigo's: only true with Indigo's vanilla lighting.
	 */
	public static @Nullable String incompatibility() {
		return switch (indigo()) {
			case ABSENT, VANILLA_LIGHTING -> null;
			case CUSTOM_LIGHTING -> "Indigo lights blocks differently from vanilla (ambient-occlusion-mode / fix-* in config/fabric/indigo-renderer.properties)";
			case UNKNOWN -> "Indigo's lighting configuration cannot be read";
		};
	}

	/**
	 * Origin of a block with an offset function, in the arithmetic of the
	 * renderer Bloom must match: vanilla adds the float offset to the float
	 * position, Indigo rounds the double sum.
	 */
	public static float origin(float position, double offset) {
		return indigo() != Indigo.ABSENT ? (float) ((double) position + offset) : position + (float) offset;
	}

	private static @Nullable Method find(String owner, String name, Class<?>... params) {
		if (!FabricLoader.getInstance().isModLoaded("fabric-api") && MODULES.stream().noneMatch(FabricLoader.getInstance()::isModLoaded)) {
			return null;
		}
		try {
			return Class.forName(owner).getMethod(name, params);
		} catch (ReflectiveOperationException | LinkageError e) {
			return null;
		}
	}

	public static boolean present() {
		return TINT_FACTORY != null || FLUID_OVERRIDE != null;
	}

	/** {@code BlockColorRegistry.getFactory(state) != null}; {@code true} if it cannot be asked. */
	public static boolean hasTintFactory(BlockState state) {
		return TINT_FACTORY != null && invoke(TINT_FACTORY, state, true) != null;
	}

	/** A mod replaced the render handler of this fluid. */
	public static boolean fluidOverridden(Fluid fluid) {
		return FLUID_OVERRIDE != null && invoke(FLUID_OVERRIDE, fluid, true) != null;
	}

	/** The block counts as transparent for the fluid overlay sprite (vanilla: {@code HalfTransparentBlock}). */
	public static boolean overlayTransparent(Block block) {
		if (BLOCK_TRANSPARENT == null) {
			return block instanceof HalfTransparentBlock;
		}
		Object r = invoke(BLOCK_TRANSPARENT, block, null);
		return r instanceof Boolean b ? b : block instanceof HalfTransparentBlock;
	}

	/** On failure returns a non-null marker when {@code failClosed}, so callers fall back to Java. */
	private static @Nullable Object invoke(Method m, Object arg, @Nullable Boolean failClosed) {
		try {
			return m.invoke(null, arg);
		} catch (ReflectiveOperationException | RuntimeException e) {
			BloomEngine.LOGGER.debug("Bloom Engine: Fabric API adapter call {} failed", m.getName(), e);
			return failClosed != null && failClosed ? Boolean.TRUE : null;
		}
	}
}
