package com.bloomclient.engine.diagnostics;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.RenderingErrors;
import com.bloomclient.engine.compatibility.CompatibilityDatabase.Status;
import com.bloomclient.engine.compatibility.CompatibilityReport;
import com.bloomclient.engine.compatibility.ShaderCompatibility;
import com.bloomclient.engine.mesh.BlockStateTables;
import com.bloomclient.engine.mixin.DebugScreenEntriesInvoker;
import com.bloomclient.engine.profiling.EngineProfiler;
import com.bloomclient.engine.profiling.GpuFrameTimer;
import com.bloomclient.engine.profiling.RenderStats;
import com.bloomclient.engine.profiling.VramProbe;
import com.bloomclient.engine.vulkan.VulkanDeviceSelection;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Bloom's F3 entries, registered in vanilla's debug entry registry: they are
 * listed in the debug options screen with the vanilla ones and toggled the
 * same way. On the first run they are switched to "in overlay" once; after
 * that the player's choice is kept.
 */
public final class BloomDebugEntries {
	public static final Identifier ENGINE = id("engine");
	public static final Identifier BLOCKS = id("block_render");
	public static final Identifier ENTITIES = id("entity_render");
	public static final Identifier GPU = id("gpu");
	public static final Identifier SHADERS = id("shaders");
	public static final Identifier COMPATIBILITY = id("compatibility");
	private static final List<Identifier> ALL = List.of(ENGINE, BLOCKS, ENTITIES, GPU, SHADERS, COMPATIBILITY);

	private static final IntervalStats INTERVAL = new IntervalStats();

	private BloomDebugEntries() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("bloom", path);
	}

	public static void register() {
		DebugScreenEntriesInvoker.bloom$register(ENGINE, entry(ENGINE, BloomDebugEntries::engine));
		DebugScreenEntriesInvoker.bloom$register(BLOCKS, entry(BLOCKS, BloomDebugEntries::blocks));
		DebugScreenEntriesInvoker.bloom$register(ENTITIES, entry(ENTITIES, BloomDebugEntries::entities));
		DebugScreenEntriesInvoker.bloom$register(GPU, entry(GPU, BloomDebugEntries::gpu));
		DebugScreenEntriesInvoker.bloom$register(SHADERS, entry(SHADERS, BloomDebugEntries::shaders));
		DebugScreenEntriesInvoker.bloom$register(COMPATIBILITY, entry(COMPATIBILITY, BloomDebugEntries::compatibility));
	}

	/** Once per installation: show Bloom's entries in the F3 overlay. */
	public static void applyDefaultsOnce(Minecraft mc) {
		Path marker = BloomEngine.dataDir().resolve("f3-defaults-applied");
		if (Files.exists(marker)) {
			return;
		}
		try {
			for (Identifier id : ALL) {
				if (mc.debugEntries.getStatus(id) == DebugScreenEntryStatus.NEVER) {
					mc.debugEntries.setStatus(id, DebugScreenEntryStatus.IN_OVERLAY);
				}
			}
			Files.createDirectories(marker.getParent());
			Files.writeString(marker, "Bloom F3 entries were enabled once; the debug options screen controls them now.\n");
		} catch (Exception e) {
			BloomEngine.LOGGER.warn("Bloom Engine: cannot apply the F3 defaults", e);
		}
	}

	private static DebugScreenEntry entry(Identifier group, Consumer<List<String>> lines) {
		return new DebugScreenEntry() {
			@Override
			public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
				List<String> out = new ArrayList<>();
				try {
					lines.accept(out);
				} catch (RuntimeException e) {
					out.add("Bloom: diagnostics error " + e.getClass().getSimpleName());
				}
				displayer.addToGroup(group, out);
			}

			@Override
			public boolean isAllowed(boolean reducedDebugInfo) {
				return true;
			}

			@Override
			public DebugEntryCategory category() {
				return DebugEntryCategory.RENDERER;
			}
		};
	}

	private static String f(String format, Object... args) {
		return String.format(Locale.ROOT, format, args);
	}

	private static void engine(List<String> out) {
		BlockStateTables t = BloomEngine.tables();
		String fluids = BloomEngine.config().nativeFluids ? "fluids on" : "fluids off";
		out.add(f("Bloom Engine: %s (%s)", BloomEngine.status(), BloomEngine.active() ? "native mesher, " + fluids : BloomEngine.reason()));
		if (t != null) {
			out.add(f("Tables: %d states, %d native fluid states, built in %d ms", t.stateCount(), t.nativeFluidStates(), t.buildNanos() / 1_000_000));
		}
	}

	private static void blocks(List<String> out) {
		IntervalStats.Window w = INTERVAL.window();
		out.add(f("Sections/s: %.0f native (%.2f ms) · %.0f Java (%.2f ms)", w.nativePerSecond(), w.nativeMeanMs(), w.vanillaPerSecond(), w.vanillaMeanMs()));
		out.add(f("Native split: snapshot %.2f ms · Rust %.2f ms", w.snapshotMeanMs(), w.meshMeanMs()));
		out.add(f("Fallback: %d blocks, %d fluids, %d sections to Java", EngineProfiler.demotedBlocks(), EngineProfiler.demotedFluids(),
				EngineProfiler.vanillaSections()));
	}

	private static void entities(List<String> out) {
		out.add(f("Entities %d: extract %.2f · submit %.2f ms", RenderStats.lastEntities(), RenderStats.recentMs(RenderStats.Stage.EXTRACT_ENTITIES),
				RenderStats.recentMs(RenderStats.Stage.SUBMIT_ENTITIES)));
		out.add(f("Block entities %d: extract %.2f · submit %.2f ms", RenderStats.lastBlockEntities(),
				RenderStats.recentMs(RenderStats.Stage.EXTRACT_BLOCK_ENTITIES), RenderStats.recentMs(RenderStats.Stage.SUBMIT_BLOCK_ENTITIES)));
		out.add(f("Particles: extract %.2f · submit %.2f ms · features %.2f ms", RenderStats.recentMs(RenderStats.Stage.EXTRACT_PARTICLES),
				RenderStats.recentMs(RenderStats.Stage.SUBMIT_PARTICLES), RenderStats.recentMs(RenderStats.Stage.PREPARE_FEATURES)));
	}

	private static void gpu(List<String> out) {
		GpuFrameTimer.request();
		DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
		double gpuMs = GpuFrameTimer.lastMs();
		out.add(f("GPU frame: %s · frame graph CPU %.2f ms", GpuFrameTimer.unavailable() ? "n/a" : Double.isNaN(gpuMs) ? "…" : f("%.2f ms", gpuMs),
				RenderStats.recentMs(RenderStats.Stage.EXECUTE_FRAME_GRAPH)));
		out.add(f("Draw calls: %.0f (%.0f draws) · terrain %s", RenderStats.recentDrawCalls(), RenderStats.recentDraws(),
				Minecraft.getInstance().levelRenderer.isChunkRenderingUsingMultiDrawIndirect() ? "multi-draw indirect" : "direct draws"));
		out.add(f("Pipeline: %s · %s", info.backendName(), info.name()));
		VramProbe.Sample vram = VramProbe.sample();
		out.add(vram.totalMiB() < 0 ? "VRAM: " + vram.source()
				: f("VRAM: %s / %d MiB (%s)", vram.usedMiB() < 0 ? "?" : Long.toString(vram.usedMiB()), vram.totalMiB(), vram.source()));
		if (info.backendName().toLowerCase(Locale.ROOT).contains("vulkan")) {
			out.add("Vulkan: " + VulkanDeviceSelection.outcome() + " · " + info.underlyingExtensions().size() + " extensions");
		}
	}

	private static void shaders(List<String> out) {
		List<String> overrides = ShaderCompatibility.overriddenShaders();
		out.add("Shaders: " + (overrides.isEmpty() ? "vanilla core shaders" : "core overrides " + String.join(", ", overrides)));
		out.add("Iris: " + ShaderCompatibility.irisState() + " · reloads " + ShaderCompatibility.reloads());
	}

	private static void compatibility(List<String> out) {
		CompatibilityReport report = BloomEngine.compatibility();
		if (report != null) {
			out.add(f("Adapters: %d native · %d automatic · %d Java fallback · %d engine off", report.count(Status.NATIVE_ADAPTER),
					report.count(Status.AUTOMATIC_ADAPTER), report.count(Status.JAVA_FALLBACK), report.count(Status.ENGINE_OFF)));
			out.add("Database: " + report.database);
		}
		List<String> recent = RenderingErrors.recent();
		out.add(f("Rendering errors: %d%s", RenderingErrors.count(), recent.isEmpty() ? "" : " · last: " + recent.getLast()));
	}
}
