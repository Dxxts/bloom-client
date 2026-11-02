package com.bloomclient.client.mixin;

import com.bloomclient.client.core.BloomPackSource;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds {@link BloomPackSource} to the client's resource pack repository (the
 * one fed by {@link ClientPackSource}); server data pack repositories are left alone.
 */
@Mixin(PackRepository.class)
abstract class PackRepositoryMixin {
	@Shadow
	@Final
	@Mutable
	private Set<RepositorySource> sources;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void bloom$addBloomAssets(RepositorySource[] given, CallbackInfo ci) {
		for (RepositorySource s : sources) {
			if (s instanceof ClientPackSource) {
				sources = ImmutableSet.<RepositorySource>builder().addAll(sources).add(new BloomPackSource()).build();
				return;
			}
		}
	}
}
