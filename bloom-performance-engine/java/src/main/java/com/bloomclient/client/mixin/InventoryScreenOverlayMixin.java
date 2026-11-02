package com.bloomclient.client.mixin;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.mods.basememory.MemoryOverlay;
import com.bloomclient.client.mods.inventory.InventoryOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slot highlights drawn over the slots and under the hover highlight:
 * Inventory Manager suggestions in the player's inventory screen, Base Memory's
 * guided item in every container screen.
 */
@Mixin(AbstractContainerScreen.class)
abstract class InventoryScreenOverlayMixin {
	@Shadow
	@Final
	protected AbstractContainerMenu menu;

	@Inject(method = "extractContents", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;extractSlotHighlightFront(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"))
	private void bloom$inventoryOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if (!BloomClient.ready()) return;
		try {
			if ((Object) this instanceof InventoryScreen) InventoryOverlay.render(graphics, menu.slots);
			MemoryOverlay.render(graphics, menu.slots);
		} catch (Throwable t) {
			BloomClient.LOGGER.error("Bloom: inventory overlay failed", t);
		}
	}
}
