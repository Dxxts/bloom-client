package com.bloomclient.engine.compatibility;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.compatibility.CompatibilityDatabase.Status;
import com.bloomclient.engine.compatibility.CompatibilityReport.ModEntry;
import com.bloomclient.engine.compatibility.CompatibilityReport.ModeSource;
import com.bloomclient.engine.compatibility.CompatibilityReport.Verdict;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;

/**
 * Bloom Compatibility Layer: chooses, for every mod, one of the three modes
 * (plus "engine off") and applies it.
 *
 * <ol>
 *   <li><b>User override</b> ({@code modModes} in {@code config/bloom-engine.json}):
 *   always wins, so every decision is reversible without code.</li>
 *   <li><b>Database</b> entry for the mod, version, loader and Minecraft
 *   version, when its status is not {@code UNTESTED}.</li>
 *   <li><b>Analysis</b>: renderer replacements and unverified terrain hooks
 *   turn the engine off; everything else is the automatic adapter, whose
 *   per-block decisions are made by {@code BlockStateTables} (unknown model
 *   classes go to the Java renderer block by block).</li>
 * </ol>
 *
 * <p>Applying a mode never touches the mod: {@code NATIVE_ADAPTER} registers
 * model classes as static, {@code JAVA_FALLBACK} sends the mod's block
 * namespaces to the Java renderer, {@code ENGINE_OFF} turns the engine off.
 * No Java code is translated and no bytecode of other mods is changed.
 */
public final class CompatibilityLayer {
	private CompatibilityLayer() {
	}

	/** Result of {@link #resolve}: what the mesher must apply. */
	public record Decisions(Set<String> fallbackNamespaces, Set<String> adapterClasses) {
	}

	public static Decisions resolve(CompatibilityReport report, CompatibilityDatabase db, Map<String, String> overrides, boolean allowUnverified,
			String minecraftVersion) {
		Set<String> fallbackNamespaces = new HashSet<>();
		Set<String> adapterClasses = new HashSet<>();
		for (ModEntry mod : report.mods) {
			Optional<CompatibilityDatabase.Entry> entry = db.find(mod.id, mod.version, "fabric", minecraftVersion);
			entry.ifPresent(e -> {
				mod.databaseStatus = e.status.name();
				mod.knownIssues = e.knownIssues;
				mod.vulkan = e.vulkan.name();
				mod.databaseTests = e.tests.size();
			});
			String override = overrides.get(mod.id);
			Status userMode = parse(override);
			if (userMode != null) {
				mod.mode = userMode;
				mod.modeSource = ModeSource.USER_OVERRIDE;
				mod.reasons.add("set by the user in config/bloom-engine.json");
			} else if (override != null) {
				mod.reasons.add("ignored unknown override '" + override + "'");
				analyse(mod, entry, allowUnverified);
			} else if (entry.isPresent() && entry.get().status != Status.UNTESTED) {
				mod.mode = entry.get().status;
				mod.modeSource = ModeSource.DATABASE;
				mod.reasons.add("compatibility database (adapter v" + entry.get().adapterVersion + ")");
				// A database entry for a mod with terrain hooks only counts if those hooks were validated.
				if (mod.verdict == Verdict.UNVERIFIED_TERRAIN_HOOKS && mod.mode != Status.ENGINE_OFF && !entry.get().terrainHooksVerified) {
					analyse(mod, entry, allowUnverified);
				}
			} else {
				analyse(mod, entry, allowUnverified);
			}
			// The Fabric API adapter also depends on how Indigo lights blocks, which no database entry can know.
			if (FabricApiAdapter.MODULES.contains(mod.id) && mod.mode != Status.ENGINE_OFF && mod.modeSource != ModeSource.USER_OVERRIDE) {
				String problem = FabricApiAdapter.incompatibility();
				if (problem != null) {
					mod.mode = Status.ENGINE_OFF;
					mod.reasons.add(problem);
				}
			}
			switch (mod.mode) {
				case JAVA_FALLBACK -> {
					if (entry.isPresent() && !entry.get().namespaces.isEmpty()) fallbackNamespaces.addAll(entry.get().namespaces);
					else fallbackNamespaces.add(mod.id);
				}
				case NATIVE_ADAPTER -> entry.ifPresent(e -> adapterClasses.addAll(e.staticModelClasses));
				default -> {
				}
			}
		}
		return new Decisions(fallbackNamespaces, adapterClasses);
	}

	private static void analyse(ModEntry mod, Optional<CompatibilityDatabase.Entry> entry, boolean allowUnverified) {
		mod.modeSource = ModeSource.ANALYSIS;
		switch (mod.verdict) {
			case REPLACES_CHUNK_RENDERER -> {
				mod.mode = Status.ENGINE_OFF;
				mod.reasons.add("replaces the chunk renderer");
			}
			case UNVERIFIED_TERRAIN_HOOKS -> {
				if (allowUnverified) {
					mod.mode = Status.AUTOMATIC_ADAPTER;
					mod.reasons.add("unverified terrain hooks, allowed by allowUnverified");
				} else {
					mod.mode = Status.ENGINE_OFF;
					mod.reasons.add("unverified hooks on " + String.join(", ", mod.renderMixinTargets));
					if (FabricApiAdapter.MODULES.contains(mod.id)) {
						mod.reasons.add("the Fabric API adapter has not been validated with this version");
					}
				}
			}
			default -> {
				mod.mode = Status.AUTOMATIC_ADAPTER;
				if (entry.isEmpty()) mod.reasons.add("not in the database: static analysis only");
			}
		}
	}

	private static Status parse(String value) {
		if (value == null) return null;
		try {
			Status s = Status.valueOf(value.trim().toUpperCase(Locale.ROOT));
			return s == Status.UNTESTED ? null : s;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Fills each mod's state counts from the tables and refines the
	 * analysis-chosen automatic mode: a mod whose blocks all fall back is
	 * reported as {@code JAVA_FALLBACK}, one whose classes were registered
	 * through the adapter API as {@code NATIVE_ADAPTER}.
	 */
	public static void applyStates(CompatibilityReport report, Map<String, CompatibilityReport.States> namespaces) {
		synchronized (report) {
			for (ModEntry mod : report.mods) {
				CompatibilityReport.States states = namespaces.get(mod.id);
				mod.states = states;
				if (states == null || mod.modeSource != ModeSource.ANALYSIS || mod.mode != Status.AUTOMATIC_ADAPTER) {
					continue;
				}
				if (states.adapter > 0) {
					mod.mode = Status.NATIVE_ADAPTER;
					mod.reasons.add(states.adapter + " block states use model classes registered through the adapter API");
				} else if (states.automatic == 0 && !states.fallback.isEmpty()) {
					mod.mode = Status.JAVA_FALLBACK;
					mod.reasons.add("no block of this mod has a verified static model");
				}
			}
		}
	}

	/**
	 * Registers the database's static model classes. Classes that are not
	 * loaded, or are not models, are skipped: a wrong entry cannot make a
	 * class native that does not exist.
	 */
	@SuppressWarnings("unchecked")
	public static void applyAdapters(Set<String> classNames) {
		for (String name : classNames) {
			try {
				Class<?> type = Class.forName(name, false, CompatibilityLayer.class.getClassLoader());
				if (BlockStateModel.class.isAssignableFrom(type)) {
					BloomRenderAdapters.registerStaticModel((Class<? extends BlockStateModel>) type);
				} else if (BlockStateModelPart.class.isAssignableFrom(type)) {
					BloomRenderAdapters.registerStaticPart((Class<? extends BlockStateModelPart>) type);
				} else {
					BloomEngine.LOGGER.warn("Bloom Engine: database adapter class {} is not a block model, ignored", name);
				}
			} catch (ClassNotFoundException | LinkageError e) {
				BloomEngine.LOGGER.warn("Bloom Engine: database adapter class {} not found, ignored", name);
			}
		}
	}
}
