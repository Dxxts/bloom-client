package com.bloomclient.client.core;

import com.bloomclient.client.BloomClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;
import org.jspecify.annotations.Nullable;

/**
 * Exposes Bloom's own assets ({@code assets/bloom/...} inside the mod jar) as
 * an always-on built-in resource pack. Without Fabric API, Minecraft does not
 * read assets from mod jars; this is what lets it load Bloom's UI font
 * ({@code bloom:ui}, a TrueType font) and language files. Only the
 * {@code bloom} namespace is shipped, so it never overrides vanilla assets.
 */
public final class BloomPackSource implements RepositorySource {
	public static final String PACK_ID = "bloom_client";
	private static final String MOD_ID = "bloom-engine";

	@Override
	public void loadPacks(Consumer<Pack> out) {
		Path root = assetsRoot();
		if (root == null) {
			BloomClient.LOGGER.warn("Bloom: assets not found in the mod jar, the UI keeps Minecraft's font");
			return;
		}
		PackLocationInfo location = new PackLocationInfo(PACK_ID, Component.literal("Bloom Client"), PackSource.BUILT_IN, Optional.empty());
		Pack.Metadata metadata = new Pack.Metadata(Component.literal("Bloom Client interface assets"), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(),
				List.of());
		out.accept(new Pack(location, new PathPackResources.PathResourcesSupplier(root), metadata,
				new PackSelectionConfig(true, Pack.Position.TOP, false)));
	}

	/** The mod root holding {@code assets/bloom} (in development, resources and classes are separate roots). */
	private static @Nullable Path assetsRoot() {
		Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(MOD_ID);
		if (mod.isEmpty()) return null;
		for (Path p : mod.get().getRootPaths()) {
			if (Files.isDirectory(p.resolve("assets").resolve("bloom"))) return p;
		}
		return null;
	}
}
