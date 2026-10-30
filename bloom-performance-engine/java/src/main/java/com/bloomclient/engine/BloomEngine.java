package com.bloomclient.engine;

import com.bloomclient.engine.bridge.NativeBridge;
import com.bloomclient.engine.bridge.NativeLibrary;
import com.bloomclient.engine.compatibility.CompatibilityAnalyzer;
import com.bloomclient.engine.compatibility.CompatibilityReport;
import com.bloomclient.engine.mesh.BlockStateTables;
import com.bloomclient.engine.profiling.EngineProfiler;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
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

	private BloomEngine() {
	}

	static void init() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		dataDir = gameDir.resolve(".bloom");
		config = EngineConfig.load(FabricLoader.getInstance().getConfigDir().resolve("bloom-engine.json"));
		compatibility = CompatibilityAnalyzer.analyze(dataDir.resolve("compatibility-report.json"));
		EngineProfiler.init(dataDir, config.statsIntervalSeconds);

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
	}

	private static void setOff(String why) {
		status = Status.OFF;
		reason = why;
		LOGGER.info("Bloom Engine: off ({}), using the vanilla renderer", why);
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
	public static synchronized void onCompilerCreated(BlockStateModelSet models, BlockColors colors) {
		if (status != Status.ACTIVE) {
			return;
		}
		BlockStateTables current = tables;
		if (current != null && current.isFor(models, colors)) {
			return;
		}
		try {
			tables = BlockStateTables.build(models, colors, current == null ? 1 : current.epoch() + 1);
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
		tables = null;
		try {
			NativeBridge.shutdown();
		} catch (Throwable ignored) {
			// Nothing else to release.
		}
	}
}
