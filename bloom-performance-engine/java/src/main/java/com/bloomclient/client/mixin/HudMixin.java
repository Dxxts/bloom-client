package com.bloomclient.client.mixin;

import com.bloomclient.client.BloomClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bloom HUD pass inside the vanilla HUD: after the hotbar, effects, boss bars
 * and scoreboard, before chat and the player list (so those stay on top).
 * Vanilla reaches this point only while the HUD is visible (not with F1).
 */
@Mixin(Hud.class)
abstract class HudMixin {
	@Inject(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Hud;extractChat(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
	private void bloom$hud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (BloomClient.ready()) {
			try {
				BloomClient.hud().render(graphics);
			} catch (Throwable t) {
				BloomClient.LOGGER.error("Bloom HUD: render pass failed", t);
			}
		}
	}
}
