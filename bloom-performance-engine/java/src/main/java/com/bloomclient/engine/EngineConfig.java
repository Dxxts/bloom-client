package com.bloomclient.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code config/bloom-engine.json}. Every field can be overridden with a
 * system property ({@code -Dbloom.engine.enabled=false}, ...), which the
 * benchmark uses for A/B runs.
 */
public final class EngineConfig {
	/** Master switch. {@code false} = pure vanilla renderer. */
	public boolean enabled = true;
	/** Validate one section out of N against the vanilla renderer (0 = off). */
	public int validateEvery = 0;
	/** Keep the engine on even if the compatibility analyzer found unverified mods. */
	public boolean allowUnverified = false;
	/** Seconds between stats reports in the log and in {@code .bloom/engine-stats.json}. */
	public int statsIntervalSeconds = 30;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static EngineConfig load(Path file) {
		EngineConfig config = new EngineConfig();
		try {
			if (Files.exists(file)) {
				EngineConfig read = GSON.fromJson(Files.readString(file), EngineConfig.class);
				if (read != null) {
					config = read;
				}
			} else {
				Files.createDirectories(file.getParent());
				Files.writeString(file, GSON.toJson(config));
			}
		} catch (IOException | RuntimeException e) {
			BloomEngine.LOGGER.warn("Bloom Engine: cannot read {}, using defaults", file, e);
		}
		config.enabled = Boolean.parseBoolean(System.getProperty("bloom.engine.enabled", Boolean.toString(config.enabled)));
		config.validateEvery = Integer.getInteger("bloom.engine.validateEvery", config.validateEvery);
		config.allowUnverified = Boolean.parseBoolean(System.getProperty("bloom.engine.allowUnverified", Boolean.toString(config.allowUnverified)));
		config.statsIntervalSeconds = Math.max(5, config.statsIntervalSeconds);
		return config;
	}
}
