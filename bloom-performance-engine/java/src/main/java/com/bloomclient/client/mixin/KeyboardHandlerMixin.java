package com.bloomclient.client.mixin;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.BloomKeys;
import com.bloomclient.client.ui.screen.BloomMenuScreen;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the Bloom Menu with its key on top of the title screen and the pause
 * menu (screens without text input), returning to them when it closes. Every
 * other screen receives the key unchanged.
 */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/Screen;keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z"), cancellable = true)
	private void bloom$menuOverScreen(long handle, int action, KeyEvent event, CallbackInfo ci) {
		if (!BloomClient.ready() || action != 1) return;
		Screen screen = Minecraft.getInstance().gui.screen();
		if (BloomKeys.isMenuKey(event) && BloomKeys.canOpenOver(screen)) {
			BloomMenuScreen.open(screen);
			ci.cancel();
		}
	}
}
