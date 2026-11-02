package com.bloomclient.engine.compatibility;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.profiling.EngineProfiler;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Static analysis of the installed mods. Nothing is modified: the analyzer
 * reads {@code fabric.mod.json}, the mixin configs and class files.
 *
 * <ol>
 *   <li><b>Synchronous</b> (feeds {@link CompatibilityLayer}): known chunk
 *   renderer replacements, mixins targeting the classes Bloom hooks or ports,
 *   other rendering transformations, and dependencies on other renderers.</li>
 *   <li><b>Background</b> (report only): graphics APIs referenced by each mod
 *   (OpenGL, Vulkan, renderpearl, vertex formats, buffers, pipelines), block
 *   and entity renderers, custom block models and shipped shaders.</li>
 * </ol>
 */
public final class CompatibilityAnalyzer {
	/** Mods that replace the chunk renderer Bloom extends. */
	private static final Set<String> CHUNK_RENDERER_REPLACEMENTS = Set.of("sodium", "embeddium", "iris", "nvidium", "vulkanmod", "canvas");

	/** Classes whose behaviour the native mesher reproduces or hooks. */
	private static final Set<String> TERRAIN_CLASSES = Set.of(
			"net/minecraft/client/renderer/chunk/SectionCompiler",
			"net/minecraft/client/renderer/chunk/SectionRenderDispatcher",
			"net/minecraft/client/renderer/chunk/VisGraph",
			"net/minecraft/client/renderer/block/ModelBlockRenderer",
			"net/minecraft/client/renderer/block/BlockModelLighter",
			"net/minecraft/client/renderer/block/BlockStateModelSet",
			"net/minecraft/client/renderer/block/FluidRenderer",
			"net/minecraft/client/renderer/block/FluidStateModelSet",
			"com/mojang/blaze3d/vertex/BufferBuilder",
			"com/mojang/blaze3d/vertex/ByteBufferBuilder");

	/** Rendering code outside the terrain: transformations Bloom reports but does not depend on. */
	private static final String[] RENDER_PREFIXES = {
			"net/minecraft/client/renderer/",
			"com/mojang/blaze3d/",
			"com/mojang/renderpearl/",
			"net/minecraft/client/gui/components/DebugScreenOverlay",
			"net/minecraft/client/particle/"};

	/** Mod ids of renderers and rendering APIs another mod may depend on. */
	private static final Set<String> RENDERERS = Set.of("sodium", "iris", "embeddium", "oculus", "canvas", "vulkanmod", "nvidium", "indium",
			"fabric-renderer-api-v1", "fabric-renderer-indigo", "immediatelyfast", "entityculling");

	private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";

	/** Constant-pool prefixes reported as "render APIs". */
	private static final String[][] API_MARKERS = {
			{"org/lwjgl/opengl/", "OpenGL (direct calls)"},
			{"org/lwjgl/vulkan/", "Vulkan (direct calls)"},
			{"com/mojang/renderpearl/", "renderpearl"},
			{"com/mojang/blaze3d/vertex/VertexFormat", "custom vertex format"},
			{"com/mojang/renderpearl/api/vertex/VertexFormat", "custom vertex format"},
			{"com/mojang/renderpearl/api/buffers/GpuBuffer", "custom GPU buffers"},
			{"com/mojang/renderpearl/api/pipeline/RenderPipeline", "custom render pipelines"},
			{"net/minecraft/client/renderer/blockentity/BlockEntityRenderer", "block entity renderer"},
			{"net/minecraft/client/renderer/entity/EntityRenderer", "entity renderer"},
			{"net/minecraft/client/renderer/chunk/ChunkSectionLayer", "terrain layers"},
			{"net/minecraft/client/renderer/rendertype/RenderType", "render types / transparency"},
			{"net/minecraft/client/renderer/PostChain", "post processing"}};

	private CompatibilityAnalyzer() {
	}

	/**
	 * Synchronous part. The caller resolves the modes with
	 * {@link CompatibilityLayer}, then calls {@link #scanInBackground}.
	 */
	public static CompatibilityReport analyze() {
		CompatibilityReport report = new CompatibilityReport();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			if (id.equals("bloom-engine") || id.equals("minecraft") || id.equals("java") || id.equals("fabricloader") || id.equals("mixinextras")) {
				continue;
			}
			CompatibilityReport.ModEntry entry = new CompatibilityReport.ModEntry();
			entry.id = id;
			entry.version = mod.getMetadata().getVersion().getFriendlyString();
			for (ModDependency dep : mod.getMetadata().getDependencies()) {
				if (RENDERERS.contains(dep.getModId()) && dep.getKind() != ModDependency.Kind.BREAKS && dep.getKind() != ModDependency.Kind.CONFLICTS) {
					entry.rendererDependencies.add(dep.getModId() + " (" + dep.getKind().getKey() + ")");
				}
			}
			try {
				scanMixins(mod, entry);
			} catch (Exception e) {
				BloomEngine.LOGGER.warn("Bloom Engine: cannot read the mixins of {}", id, e);
				// Unknown hooks are treated as unverified rather than ignored.
				entry.renderMixinTargets.add("<unreadable mixin config>");
			}
			if (CHUNK_RENDERER_REPLACEMENTS.contains(id)) {
				entry.verdict = CompatibilityReport.Verdict.REPLACES_CHUNK_RENDERER;
			} else if (!entry.renderMixinTargets.isEmpty()) {
				entry.verdict = CompatibilityReport.Verdict.UNVERIFIED_TERRAIN_HOOKS;
			} else if (!entry.renderTransformations.isEmpty()) {
				entry.verdict = CompatibilityReport.Verdict.RENDERS_OUTSIDE_TERRAIN;
			}
			report.mods.add(entry);
		}
		return report;
	}

	public static void write(Path reportFile, CompatibilityReport report) {
		synchronized (report) {
			EngineProfiler.write(reportFile, report);
		}
	}

	/** Background part: APIs, block models and shaders of every mod (report only). */
	public static void scanInBackground(Path reportFile, CompatibilityReport report) {
		Thread scan = new Thread(() -> {
			for (CompatibilityReport.ModEntry entry : report.mods) {
				FabricLoader.getInstance().getModContainer(entry.id).ifPresent(mod -> scanClasses(mod, entry));
			}
			report.classScanComplete = true;
			write(reportFile, report);
		}, "bloom-compatibility-scan");
		scan.setDaemon(true);
		scan.setPriority(Thread.MIN_PRIORITY);
		scan.start();
	}

	private static void scanMixins(ModContainer mod, CompatibilityReport.ModEntry entry) throws IOException {
		for (Path root : mod.getRootPaths()) {
			Path fmj = root.resolve("fabric.mod.json");
			if (!Files.exists(fmj)) continue;
			JsonObject meta = JsonParser.parseString(Files.readString(fmj)).getAsJsonObject();
			if (!meta.has("mixins")) continue;
			for (JsonElement e : meta.getAsJsonArray("mixins")) {
				String config = e.isJsonObject() ? e.getAsJsonObject().get("config").getAsString() : e.getAsString();
				Path configPath = root.resolve(config);
				if (!Files.exists(configPath)) continue;
				JsonObject cfg = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
				String pkg = cfg.has("package") ? cfg.get("package").getAsString() : "";
				for (String section : new String[] {"mixins", "client", "server"}) {
					if (!cfg.has(section)) continue;
					for (JsonElement m : cfg.getAsJsonArray(section)) {
						Path cls = root.resolve((pkg + "." + m.getAsString()).replace('.', '/') + ".class");
						if (Files.exists(cls)) {
							for (String target : mixinTargets(Files.readAllBytes(cls))) {
								if (TERRAIN_CLASSES.contains(target)) {
									entry.renderMixinTargets.add(target.replace('/', '.'));
								} else if (isRenderClass(target)) {
									entry.renderTransformations.add(target.replace('/', '.'));
								}
							}
						}
					}
				}
			}
		}
	}

	static boolean isRenderClass(String internalName) {
		for (String prefix : RENDER_PREFIXES) {
			if (internalName.startsWith(prefix)) return true;
		}
		return false;
	}

	static List<String> mixinTargets(byte[] classBytes) {
		List<String> targets = new ArrayList<>();
		new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (!MIXIN_ANNOTATION.equals(descriptor)) return null;
				return new AnnotationVisitor(Opcodes.ASM9) {
					@Override
					public AnnotationVisitor visitArray(String name) {
						return new AnnotationVisitor(Opcodes.ASM9) {
							@Override
							public void visit(String n, Object value) {
								if (value instanceof Type t) targets.add(t.getInternalName());
								else if (value instanceof String s) targets.add(s.replace('.', '/'));
							}
						};
					}
				};
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return targets;
	}

	private static void scanClasses(ModContainer mod, CompatibilityReport.ModEntry entry) {
		for (Path root : mod.getRootPaths()) {
			try (Stream<Path> files = Files.walk(root)) {
				files.forEach(p -> {
					String name = p.toString();
					if (name.contains("/shaders/") && (name.endsWith(".vsh") || name.endsWith(".fsh") || name.endsWith(".glsl") || name.endsWith(".json"))) {
						entry.shipsShaders = true;
					}
					if (!name.endsWith(".class")) return;
					try (InputStream in = Files.newInputStream(p)) {
						byte[] bytes = in.readAllBytes();
						String pool = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
						for (String[] marker : API_MARKERS) {
							if (pool.contains(marker[0])) entry.renderApis.add(marker[1]);
						}
						ClassReader reader = new ClassReader(bytes);
						for (String itf : reader.getInterfaces()) {
							if (itf.equals("net/minecraft/client/renderer/block/dispatch/BlockStateModel")
									|| itf.equals("net/minecraft/client/renderer/block/dispatch/BlockStateModelPart")) {
								entry.customBlockModels++;
							}
						}
					} catch (Exception ignored) {
						// A broken class file is the mod's problem, not the report's.
					}
				});
			} catch (IOException ignored) {
				// Root not walkable (e.g. nested jar already closed): skip.
			}
		}
		if (entry.verdict == CompatibilityReport.Verdict.NO_RENDER_HOOKS && !entry.renderApis.isEmpty()) {
			entry.verdict = CompatibilityReport.Verdict.RENDERS_OUTSIDE_TERRAIN;
		}
	}
}
