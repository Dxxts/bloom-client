package com.bloomclient.client.mixin;

import com.bloomclient.client.BloomKeys;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds Bloom's key bindings to the vanilla list before options.txt is read,
 * so they are saved, loaded and shown in Controls like vanilla keys.
 * Compatible with other mods doing the same (only missing keys are added).
 */
@Mixin(Options.class)
abstract class OptionsMixin {
	@Shadow
	@Final
	@Mutable
	public KeyMapping[] keyMappings;

	@Inject(method = "load", at = @At("HEAD"))
	private void bloom$addKeys(CallbackInfo ci) {
		List<KeyMapping> list = new ArrayList<>(Arrays.asList(keyMappings));
		boolean changed = false;
		for (KeyMapping key : BloomKeys.all()) {
			if (!list.contains(key)) {
				list.add(key);
				changed = true;
			}
		}
		if (changed) {
			keyMappings = list.toArray(KeyMapping[]::new);
		}
	}
}
