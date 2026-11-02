package com.bloomclient.client.mods.basememory;

import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Draw;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * While Base Memory is guiding the player to an item, slots holding that item
 * glow in any open container screen (mint), and shulker boxes or bundles
 * holding it are outlined (amber). Drawing only; the player moves the items.
 */
public final class MemoryOverlay {
	private MemoryOverlay() {
	}

	/** Called from container screens with the pose translated to the screen's top-left corner. */
	public static void render(GuiGraphicsExtractor g, List<Slot> slots) {
		BaseMemoryMod mod = BaseMemoryMod.active();
		Item item = mod == null ? null : mod.highlightItem();
		if (item == null) return;
		for (Slot slot : slots) {
			if (!slot.isActive()) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			int color = 0;
			if (stack.is(item)) {
				color = BloomTheme.ACCENT;
			} else {
				boolean[] inside = {false};
				Stacks.forEachInside(stack, (holder, inner) -> inside[0] |= inner.is(item));
				if (inside[0]) color = BloomTheme.WARNING;
			}
			if (color != 0) {
				Draw.rect(g, slot.x, slot.y, 16, 16, BloomTheme.withAlpha(color, 0.22f));
				Draw.roundRectOutline(g, slot.x - 1, slot.y - 1, 18, 18, 1, 1, color);
			}
		}
	}
}
