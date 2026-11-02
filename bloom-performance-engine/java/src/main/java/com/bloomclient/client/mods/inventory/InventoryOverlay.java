package com.bloomclient.client.mods.inventory;

import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Draw;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Highlights the Inventory Manager's suggestions in the vanilla inventory
 * screen: the slot to take an item from (amber), where to put it (mint) and
 * nearly broken equipment (red). Drawing only; the player moves the items.
 */
public final class InventoryOverlay {
	private InventoryOverlay() {
	}

	/** Called from the inventory screen with the pose translated to the screen's top-left corner. */
	public static void render(GuiGraphicsExtractor g, List<Slot> slots) {
		InventoryManagerMod mod = InventoryManagerMod.active();
		if (mod == null || !mod.overlay.on()) return;
		List<Organizer.Suggestion> suggestions = mod.suggestions;
		if (suggestions.isEmpty()) return;
		for (Slot slot : slots) {
			if (!(slot.container instanceof Inventory) || !slot.isActive()) continue;
			int ours = toAnalysisSlot(slot.getContainerSlot());
			if (ours < 0) continue;
			int color = 0;
			for (Organizer.Suggestion s : suggestions) {
				if (s.to() == ours && (s.type() == Organizer.Type.MOVE || s.type() == Organizer.Type.EQUIP)) color = BloomTheme.ACCENT;
				else if (s.from() == ours && s.type() == Organizer.Type.WARN && color == 0) color = BloomTheme.DANGER;
				else if (s.from() == ours && color == 0) color = BloomTheme.WARNING;
			}
			if (color != 0) {
				Draw.rect(g, slot.x, slot.y, 16, 16, BloomTheme.withAlpha(color, 0.18f));
				Draw.roundRectOutline(g, slot.x - 1, slot.y - 1, 18, 18, 1, 1, color);
			}
		}
	}

	/** Inventory container index → {@link InventoryAnalysis} slot number. */
	static int toAnalysisSlot(int index) {
		if (index >= 0 && index < InventoryAnalysis.MAIN_SIZE) return index;
		EquipmentSlot eq = Inventory.EQUIPMENT_SLOT_MAPPING.get(index);
		if (eq == null) return -1;
		if (eq == EquipmentSlot.OFFHAND) return InventoryAnalysis.OFFHAND;
		for (int k = 0; k < 4; k++) if (InventoryAnalysis.ARMOR_SLOTS[k] == eq) return InventoryAnalysis.ARMOR_BASE + k;
		return -1;
	}
}
