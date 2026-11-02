package com.bloomclient.engine;

import com.bloomclient.engine.bridge.NativeBridge;
import com.bloomclient.engine.bridge.NativeLibrary;
import com.bloomclient.engine.compatibility.CompatibilityAnalyzer;
import com.bloomclient.engine.compatibility.CompatibilityDatabase;
import com.bloomclient.engine.compatibility.CompatibilityLayer;
import com.bloomclient.engine.compatibility.CompatibilityReport;
import com.bloomclient.engine.compatibility.ShaderCompatibility;
import com.bloomclient.engine.mesh.BlockStateTables;
import com.bloomclient.engine.profiling.EngineProfiler;
import java.nio.file.Path;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Engine lifecycle: configuration, compatibility check, native library,
 * tables and the fallback switch.
 *
 * <p>The engine is {@link Status#ACTIVE} only when every check passed. Any
 * native error calls {@link #fail}: the engine turns off for the rest of the
 * session, native tables are released and every section is compiled by the
 * vanilla renderer again. Networking, accounts and gameplay are never
 * involved.
 */
public final class BloomEngine {
	public static final Logger LOGGER = LoggerFactory.getLogger("bloom-engine");

	public enum Status {
		/** Disabled by configuration or by the compatibility analyzer. */
		OFF,
		ACTIVE,
		/** Disabled after an error. */
		FAILED
	}

	private static volatile Status status = Status.OFF;
	private static volatile String reason = "not initialised";
	private static volatile @Nullable BlockStateTables tables;
	private static EngineConfig config = new EngineConfig();
	private static Path dataDir = Path.of(".bloom");
	private static @Nullable CompatibilityReport compatibility;
	private static CompatibilityDatabase database = CompatibilityDatabase.empty("not loaded");
	private static CompatibilityLayer.Decisions decisions = new CompatibilityLayer.Decisions(Set.of(), Set.of());
	private static Path reportFile = Path.of(".bloom", "compatibility-report.json");

	private BloomEngine() {
	}

	static void init() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		dataDir = gameDir.resolve(".bloom");
		config = EngineConfig.load(FabricLoader.getInstance().getConfigDir().resolve("bloom-engine.json"));
		EngineProfiler.init(dataDir, config.statsIntervalSeconds);
		reportFile = dataDir.resolve("compatibility-report.json");
		String minecraft = FabricLoader.getInstance().getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		String downloaded = System.getProperty("bloom.compatDb");
		database = CompatibilityDatabase.load(downloaded == null || downloaded.isBlank() ? null : Path.of(downloaded));
		compatibility = CompatibilityAnalyzer.analyze();
		decisions = CompatibilityLayer.resolve(compatibility, database, config.modModes, config.allowUnverified, minecraft);
		CompatibilityLayer.applyAdapters(decisions.adapterClasses());
		compatibility.database = database.source() + " (sequence " + database.sequence() + ", " + database.size() + " entries)";
		compatibility.databaseProblems = database.problems();
		for (String problem : database.problems()) {
			LOGGER.warn("Bloom Engine: compatibility database: {}", problem);
		}
		ShaderCompatibility.detectMods(compatibility);
		CompatibilityAnalyzer.scanInBackground(reportFile, compatibility);

		if (!config.enabled) {
			setOff("disabled in config/bloom-engine.json");
			return;
		}
		if (compatibility.blocking() && !config.allowUnverified) {
			setOff("compatibility: " + compatibility.blockingReason());
			return;
		}
		try {
			NativeLibrary.load(dataDir.resolve("native"));
		} catch (Throwable t) {
			status = Status.FAILED;
			reason = "native library: " + t.getMessage();
			LOGGER.error("Bloom Engine: cannot load the native library, using the vanilla renderer", t);
			return;
		}
		status = Status.ACTIVE;
		reason = "active";
		LOGGER.info("Bloom Engine: native chunk mesher active");
		writeReport();
	}

	private static void setOff(String why) {
		status = Status.OFF;
		reason = why;
		LOGGER.info("Bloom Engine: off ({}), using the vanilla renderer", why);
		writeReport();
	}

	private static void writeReport() {
		CompatibilityReport report = compatibility;
		if (report != null) {
			report.engineStatus = status + ": " + reason;
			CompatibilityAnalyzer.write(reportFile, report);
		}
	}

	public static CompatibilityDatabase database() {
		return database;
	}

	public static boolean active() {
		return status == Status.ACTIVE && tables != null;
	}

	public static Status status() {
		return status;
	}

	public static String reason() {
		return reason;
	}

	public static EngineConfig config() {
		return config;
	}

	public static Path dataDir() {
		return dataDir;
	}

	public static @Nullable BlockStateTables tables() {
		return tables;
	}

	public static @Nullable CompatibilityReport compatibility() {
		return compatibility;
	}

	/**
	 * Called whenever Minecraft creates a {@code SectionCompiler} (resource
	 * reload, graphics option change). Rebuilds the native tables for the new
	 * baked models; snapshots built for the old tables are rejected natively.
	 */
	public static synchronized void onCompilerCreated(BlockStateModelSet models, FluidStateModelSet fluids, BlockColors colors) {
		if (compatibility != null) {
			try {
				ShaderCompatibility.onResourcesReloaded(net.minecraft.client.Minecraft.getInstance().getResourceManager(), compatibility);
			} catch (RuntimeException e) {
				LOGGER.debug("Bloom Engine: shader detection failed", e);
			}
		}
		if (status != Status.ACTIVE) {
			return;
		}
		BlockStateTables current = tables;
		if (current != null && current.isFor(models, fluids, colors)) {
			return;
		}
		try {
			BlockStateTables built = BlockStateTables.build(models, fluids, colors, current == null ? 1 : current.epoch() + 1, decisions.fallbackNamespaces());
			tables = built;
			if (compatibility != null) {
				CompatibilityLayer.applyStates(compatibility, built.namespaces());
				synchronized (compatibility) {
					compatibility.fallbackStates.clear();
					built.fallbackCounts().forEach((k, v) -> compatibility.fallbackStates.put(k.name(), v));
				}
				writeReport();
			}
		} catch (Throwable t) {
			fail("building native tables", t);
		}
	}

	/** Turns the engine off for this session. Safe to call from any thread. */
	public static synchronized void fail(String where, @Nullable Throwable error) {
		if (status == Status.FAILED) {
			return;
		}
		status = Status.FAILED;
		String nativeError = "";
		try {
			nativeError = NativeLibrary.lastError();
		} catch (Throwable ignored) {
			// The library may be the thing that failed.
		}
		reason = where + (nativeError.isEmpty() ? "" : ": " + nativeError);
		LOGGER.error("Bloom Engine: disabled after an error while {}; sections are compiled by the vanilla renderer from now on", reason, error);
		RenderingErrors.record(reason);
		writeReport();
		tables = null;
		try {
			NativeBridge.shutdown();
		} catch (Throwable ignored) {
			// Nothing else to release.
		}
	}
}
