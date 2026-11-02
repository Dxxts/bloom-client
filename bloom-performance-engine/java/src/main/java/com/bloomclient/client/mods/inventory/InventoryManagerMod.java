package com.bloomclient.client.mods.inventory;

import com.bloomclient.client.api.BloomMod;
import com.bloomclient.client.api.ModCategory;
import com.bloomclient.client.api.ModContext;
import com.bloomclient.client.api.ModMetadata;
import com.bloomclient.client.api.event.ClientTickEvent;
import com.bloomclient.client.api.event.InventoryChangedEvent;
import com.bloomclient.client.api.event.WorldJoinEvent;
import com.bloomclient.client.api.event.WorldLeaveEvent;
import com.bloomclient.client.api.settings.Setting;
import com.bloomclient.client.api.settings.SettingsGroup;
import com.bloomclient.client.ui.Icon;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Inventory Manager: analysis of what the player carries, organization
 * suggestions from customizable hotbar rules, resource insights (running
 * out, important items, space usage) and goals compared against real
 * inventory counts and real recipes.
 *
 * <p>Read-only towards the game: it never moves items, clicks slots or sends
 * packets. The analysis runs only when the inventory changes.
 */
public final class InventoryManagerMod implements BloomMod {
	public static final String ID = "bloom-inventory-manager";
	private static @Nullable InventoryManagerMod active;

	private @Nullable ModContext ctx;
	InventoryData data;
	volatile InventoryAnalysis analysis = InventoryAnalysis.empty();
	volatile List<Organizer.Suggestion> suggestions = List.of();
	private boolean dirty = true;

	Setting.Int lowDurability, almostFull;
	Setting.Bool overlay, suggestArmor, suggestFood, suggestMerge;

	@Override
	public ModMetadata metadata() {
		return new ModMetadata(ID, "bloom.im.name", "bloom.im.description", "1.0.0", "Bloom Client", ModCategory.INVENTORY, Icon.BAG);
	}

	/** The enabled instance, for the inventory screen overlay. */
	static @Nullable InventoryManagerMod active() {
		return active;
	}

	@Override
	public void onInitialize(ModContext context) {
		this.ctx = context;
		SettingsGroup s = context.settings();
		s.section("bloom.im.settings.analysis");
		lowDurability = s.integer("lowDurability", 15, 5, 50, 5, "%", "bloom.im.settings.low").describe("bloom.im.settings.low.desc");
		almostFull = s.integer("almostFull", 3, 0, 9, 1, "bloom.im.settings.almost_full").describe("bloom.im.settings.almost_full.desc");
		s.section("bloom.im.settings.organization");
		overlay = s.bool("overlay", true, "bloom.im.settings.overlay").describe("bloom.im.settings.overlay.desc");
		suggestArmor = s.bool("suggestArmor", true, "bloom.im.settings.armor");
		suggestFood = s.bool("suggestFood", true, "bloom.im.settings.food");
		suggestMerge = s.bool("suggestMerge", true, "bloom.im.settings.merge");
		for (Setting<?> setting : s.all()) setting.onChange(v -> dirty = true);

		data = new InventoryData(context.storage().globalDir());
		data.loadRules();
		context.registerWidget(new InventoryWidgets.Summary(this));
		context.registerWidget(new InventoryWidgets.Selected(this));
		context.registerWidget(new InventoryWidgets.Durability(this));
		context.registerWidget(new InventoryWidgets.FreeSlots(this));
		context.registerWidget(new InventoryWidgets.GoalProgress(this));
		context.registerPage(new InventoryManagerPage(this));
	}

	@Override
	public void onEnable(ModContext context) {
		active = this;
		dirty = true;
		context.on(InventoryChangedEvent.class, e -> dirty = true);
		context.on(WorldJoinEvent.class, e -> {
			data.openWorld(context.storage().world());
			dirty = true;
		});
		context.on(WorldLeaveEvent.class, e -> {
			data.save(true);
			data.openWorld(null);
			analysis = InventoryAnalysis.empty();
			suggestions = List.of();
		});
		context.on(ClientTickEvent.class, e -> {
			if (dirty && e.every(5)) recompute();
			if (e.every(600)) data.save(false);
		});
		if (context.game().world() != null) data.openWorld(context.storage().world());
	}

	@Override
	public void onDisable(ModContext context) {
		if (active == this) active = null;
		data.save(true);
		analysis = InventoryAnalysis.empty();
		suggestions = List.of();
	}

	ModContext ctx() {
		return ctx;
	}

	/** A requirement of the active goal that the player does not carry enough of yet. */
	public record GoalNeed(String label, List<Item> items, int missing) {
	}

	/** For other Bloom mods: name of the active goal, or null (also when the Inventory Manager is off). */
	public static @Nullable String activeGoalName() {
		InventoryManagerMod mod = active;
		InventoryData.Goal goal = mod == null ? null : mod.data.active();
		return goal == null ? null : goal.name;
	}

	/** For other Bloom mods: what the active goal still needs, compared with the player's inventory right now. */
	public static List<GoalNeed> activeGoalNeeds() {
		InventoryManagerMod mod = active;
		InventoryData.Goal goal = mod == null ? null : mod.data.active();
		if (goal == null) return List.of();
		List<GoalNeed> out = new ArrayList<>();
		for (InventoryData.Requirement r : goal.requirements) {
			int missing = r.count - r.owned(mod.analysis);
			if (missing <= 0) continue;
			List<Item> items = new ArrayList<>();
			for (String id : r.items) {
				Item item = InventoryData.item(id);
				if (item != null) items.add(item);
			}
			if (!items.isEmpty()) out.add(new GoalNeed(r.label.isBlank() ? new ItemStack(items.get(0)).getHoverName().getString() : r.label, items, missing));
		}
		return out;
	}

	void markDirty() {
		dirty = true;
	}

	void recompute() {
		dirty = false;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			analysis = InventoryAnalysis.empty();
			suggestions = List.of();
			return;
		}
		InventoryAnalysis a = InventoryAnalysis.of(player, lowDurability.get());
		analysis = a;
		suggestions = Organizer.compute(a, data, new Organizer.Options(suggestArmor.on(), suggestFood.on(), suggestMerge.on(), almostFull.get()));
	}
}
