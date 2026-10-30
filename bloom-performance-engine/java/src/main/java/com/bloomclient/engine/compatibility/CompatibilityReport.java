package com.bloomclient.engine.compatibility;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/** Result of {@link CompatibilityAnalyzer}, serialized to {@code .bloom/compatibility-report.json}. */
public final class CompatibilityReport {
	public enum Verdict {
		/** No interaction with the world renderer found. */
		NO_RENDER_HOOKS,
		/** Uses rendering APIs Bloom does not replace (entities, GUI, own shaders): unaffected. */
		RENDERS_OUTSIDE_TERRAIN,
		/** Hooks classes the native mesher also hooks: not verified, engine off unless allowed. */
		UNVERIFIED_TERRAIN_HOOKS,
		/** Replaces the chunk renderer: Bloom turns itself off. */
		REPLACES_CHUNK_RENDERER
	}

	public static final class ModEntry {
		public String id;
		public String version;
		public Verdict verdict = Verdict.NO_RENDER_HOOKS;
		/** Rendering classes targeted by the mod's mixins. */
		public TreeSet<String> renderMixinTargets = new TreeSet<>();
		/** Rendering APIs referenced by the mod's classes (filled by the background scan). */
		public TreeSet<String> renderApis = new TreeSet<>();
		public int customBlockModels;
		public boolean shipsShaders;
	}

	public String engineStatus = "";
	public final List<ModEntry> mods = new ArrayList<>();
	public final TreeMap<String, Integer> fallbackStates = new TreeMap<>();
	public boolean classScanComplete;

	public boolean blocking() {
		return mods.stream().anyMatch(m -> m.verdict == Verdict.REPLACES_CHUNK_RENDERER || m.verdict == Verdict.UNVERIFIED_TERRAIN_HOOKS);
	}

	public String blockingReason() {
		StringBuilder sb = new StringBuilder();
		for (ModEntry m : mods) {
			if (m.verdict == Verdict.REPLACES_CHUNK_RENDERER || m.verdict == Verdict.UNVERIFIED_TERRAIN_HOOKS) {
				if (!sb.isEmpty()) sb.append(", ");
				sb.append(m.id).append(" (").append(m.verdict.name().toLowerCase()).append(')');
			}
		}
		return sb.toString();
	}
}
