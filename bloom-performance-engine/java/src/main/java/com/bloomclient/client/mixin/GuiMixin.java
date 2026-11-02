package com.bloomclient.client.mixin;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.BloomKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bloom key bindings during gameplay (vanilla calls handleKeybinds only when no screen is open). */
@Mixin(Gui.class)
abstract class GuiMixin {
	@Inject(method = "handleKeybinds", at = @At("TAIL"))
	private void bloom$keys(CallbackInfo ci) {
		if (BloomClient.ready()) {
			BloomKeys.handleInGame(Minecraft.getInstance());
		}
	}
}
