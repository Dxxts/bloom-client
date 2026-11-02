package com.bloomclient.engine.compatibility;

import com.bloomclient.engine.BloomEngine;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * What shader systems are in use and how Bloom relates to them.
 *
 * <ul>
 *   <li><b>Vanilla core shaders</b> and resource packs that replace them
 *   ({@code shaders/core/terrain.*}, {@code block.*}, ...): Bloom writes the
 *   same vertex data in the same format, so they apply unchanged. Detected
 *   at every resource reload (F3+T included).</li>
 *   <li><b>Iris shaderpacks</b>: Iris needs Sodium, which replaces the chunk
 *   renderer, so the Bloom mesher is off and Iris renders everything. Bloom
 *   only reports the pack in use.</li>
 *   <li><b>Post processing</b> ({@code PostChain}: entity outline, spectator
 *   effects): not touched by Bloom.</li>
 * </ul>
 * Bloom has no shaderpack loader of its own.
 */
public final class ShaderCompatibility {
	private static final String[] CORE_SHADERS = {"terrain.vsh", "terrain.fsh", "block.vsh", "block.fsh", "entity.vsh", "entity.fsh", "particle.vsh",
			"particle.fsh", "sky.vsh", "sky.fsh", "clouds.vsh", "clouds.fsh"};

	private static volatile String irisState = "not installed";
	private static volatile List<String> overriddenShaders = List.of();
	private static volatile int reloads;

	private ShaderCompatibility() {
	}

	/** At startup: Iris and its configuration. */
	public static void detectMods(CompatibilityReport report) {
		if (FabricLoader.getInstance().isModLoaded("iris")) {
			Properties props = new Properties();
			Path file = FabricLoader.getInstance().getConfigDir().resolve("iris.properties");
			try (Reader in = Files.newBufferedReader(file)) {
				props.load(in);
			} catch (IOException e) {
				// No configuration yet: Iris defaults (shaders off until a pack is chosen).
			}
			boolean enabled = Boolean.parseBoolean(props.getProperty("enableShaders", "false"));
			String pack = props.getProperty("shaderPack", "");
			irisState = enabled && !pack.isBlank() ? "shaderpack '" + pack + "'" : "installed, no shaderpack active";
		}
		update(report);
	}

	/** At every resource reload: core shaders replaced by resource packs. */
	public static void onResourcesReloaded(ResourceManager resources, CompatibilityReport report) {
		List<String> overridden = new ArrayList<>();
		for (String name : CORE_SHADERS) {
			try {
				List<Resource> stack = resources.getResourceStack(Identifier.withDefaultNamespace("shaders/core/" + name));
				if (!stack.isEmpty()) {
					String pack = stack.getLast().sourcePackId();
					if (!pack.equals("vanilla")) {
						overridden.add(name + " (" + pack + ")");
					}
				}
			} catch (RuntimeException e) {
				BloomEngine.LOGGER.debug("Bloom Engine: cannot inspect shader {}", name, e);
			}
		}
		overriddenShaders = List.copyOf(overridden);
		reloads++;
		update(report);
	}

	private static void update(CompatibilityReport report) {
		if (report == null) return;
		Map<String, String> shaders = new TreeMap<>();
		shaders.put("iris", irisState);
		shaders.put("coreShaderOverrides", overriddenShaders.isEmpty() ? "none (vanilla)" : String.join(", ", overriddenShaders));
		shaders.put("resourceReloads", Integer.toString(reloads));
		synchronized (report) {
			report.shaders = new TreeMap<>(shaders);
		}
	}

	/**
	 * Iris state for display. On Vulkan Iris loads but does not apply the
	 * shaderpack (verified with Iris 1.11.7 on 26.3, see COMPATIBILITA.md).
	 */
	public static String irisState() {
		String state = irisState;
		if (state.startsWith("shaderpack") && isVulkan()) {
			return state + " NOT applied: Iris needs OpenGL";
		}
		return state;
	}

	private static boolean isVulkan() {
		try {
			return com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(java.util.Locale.ROOT).contains("vulkan");
		} catch (RuntimeException e) {
			return false;
		}
	}

	public static List<String> overriddenShaders() {
		return overriddenShaders;
	}

	public static int reloads() {
		return reloads;
	}
}
