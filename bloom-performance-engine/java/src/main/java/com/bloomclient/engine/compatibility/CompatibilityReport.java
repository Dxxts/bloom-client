package com.bloomclient.engine.compatibility;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/** Result of {@link CompatibilityAnalyzer} and {@link CompatibilityLayer}, serialized to {@code .bloom/compatibility-report.json}. */
public final class CompatibilityReport {
	/** What the static analysis found (before the database and user overrides). */
	public enum Verdict {
		/** No interaction with the world renderer found. */
		NO_RENDER_HOOKS,
		/** Uses rendering APIs Bloom does not replace (entities, GUI, own shaders): unaffected. */
		RENDERS_OUTSIDE_TERRAIN,
		/** Hooks classes the native mesher also hooks: not verified. */
		UNVERIFIED_TERRAIN_HOOKS,
		/** Replaces the chunk renderer: Bloom turns itself off. */
		REPLACES_CHUNK_RENDERER
	}

	/** Who chose a mod's mode. */
	public enum ModeSource {
		ANALYSIS,
		DATABASE,
		USER_OVERRIDE
	}

	public static final class States {
		/** Meshed natively through vanilla's static model classes (automatic adapter). */
		public int automatic;
		/** Meshed natively through classes registered by an adapter. */
		public int adapter;
		/** Tesselated by the Java renderer, by reason. */
		public TreeMap<String, Integer> fallback = new TreeMap<>();
	}

	public static final class ModEntry {
		public String id;
		public String version;
		public Verdict verdict = Verdict.NO_RENDER_HOOKS;
		public CompatibilityDatabase.Status mode = CompatibilityDatabase.Status.AUTOMATIC_ADAPTER;
		public ModeSource modeSource = ModeSource.ANALYSIS;
		public List<String> reasons = new ArrayList<>();
		/** Terrain classes targeted by the mod's mixins (the ones Bloom hooks or ports). */
		public TreeSet<String> renderMixinTargets = new TreeSet<>();
		/** Other rendering classes the mod transforms (entities, frame, GPU layer...). */
		public TreeSet<String> renderTransformations = new TreeSet<>();
		/** Rendering APIs referenced by the mod's classes (filled by the background scan). */
		public TreeSet<String> renderApis = new TreeSet<>();
		/** Other renderers the mod depends on or recommends (fabric.mod.json). */
		public TreeSet<String> rendererDependencies = new TreeSet<>();
		public int customBlockModels;
		public boolean shipsShaders;
		public States states;
		/** Database entry, if one matched. */
		public String databaseStatus;
		public List<String> knownIssues = new ArrayList<>();
		public String vulkan;
		public int databaseTests;
	}

	public String engineStatus = "";
	public String database = "";
	public List<String> databaseProblems = new ArrayList<>();
	public final List<ModEntry> mods = new ArrayList<>();
	public final TreeMap<String, Integer> fallbackStates = new TreeMap<>();
	/** Shader systems found (see {@code ShaderCompatibility}). */
	public TreeMap<String, String> shaders = new TreeMap<>();
	public boolean classScanComplete;

	public boolean blocking() {
		return mods.stream().anyMatch(m -> m.mode == CompatibilityDatabase.Status.ENGINE_OFF);
	}

	public String blockingReason() {
		StringBuilder sb = new StringBuilder();
		for (ModEntry m : mods) {
			if (m.mode == CompatibilityDatabase.Status.ENGINE_OFF) {
				if (!sb.isEmpty()) sb.append(", ");
				sb.append(m.id).append(" (").append(String.join("; ", m.reasons)).append(')');
			}
		}
		return sb.toString();
	}

	public long count(CompatibilityDatabase.Status mode) {
		return mods.stream().filter(m -> m.mode == mode).count();
	}
}
