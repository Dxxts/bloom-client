package com.bloomclient.client.mixin;

import com.bloomclient.client.ui.Tr;
import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves Bloom's translation keys (bloom.*, key.bloom.*, key.category.bloom.*)
 * from Bloom's own language files: without Fabric API, Minecraft does not load
 * language files from mod jars. Other keys are untouched.
 */
@Mixin(ClientLanguage.class)
abstract class ClientLanguageMixin {
	@Inject(method = "getOrDefault", at = @At("HEAD"), cancellable = true)
	private void bloom$get(String key, String fallback, CallbackInfoReturnable<String> cir) {
		if (Tr.handles(key)) {
			String value = Tr.lookup(key);
			if (value != null) cir.setReturnValue(value);
		}
	}

	@Inject(method = "has", at = @At("HEAD"), cancellable = true)
	private void bloom$has(String key, CallbackInfoReturnable<Boolean> cir) {
		if (Tr.handles(key) && Tr.lookup(key) != null) {
			cir.setReturnValue(true);
		}
	}
}
