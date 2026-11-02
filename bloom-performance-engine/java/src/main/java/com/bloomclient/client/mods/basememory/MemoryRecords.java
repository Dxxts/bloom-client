package com.bloomclient.client.mods.basememory;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/** What Base Memory remembers: containers the player opened and villagers the player traded with. */
final class MemoryRecords {
	private MemoryRecords() {
	}

	/** One non-empty slot of a container, as the player last saw it. */
	record SlotStack(int slot, ItemStack stack) {
	}

	/** A container the player opened (chest, barrel, shulker box, ender chest, hopper, dispenser…). */
	static final class StoredContainer {
		/** Key of the ender chest: its contents are the same in every ender chest of the world. */
		static final String ENDER = "ender";

		final String key;
		String dim = "";
		int x, y, z;
		/** Other half of a double chest. */
		boolean twin;
		int tx, ty, tz;
		String block = "minecraft:chest";
		@Nullable String customName;
		long seen;
		int size;
		List<SlotStack> slots = new ArrayList<>();
		private transient @Nullable ItemStack icon;
		private transient @Nullable String blockName;

		StoredContainer(String key) {
			this.key = key;
		}

		static String keyFor(String dim, BlockPos pos, @Nullable BlockPos twin) {
			long a = pos.asLong();
			if (twin != null) a = Math.min(a, twin.asLong());
			return dim + "|" + a;
		}

		boolean ender() {
			return ENDER.equals(key);
		}

		Block blockType() {
			Identifier id = Identifier.tryParse(block);
			Block b = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
			return b == null ? Blocks.CHEST : b;
		}

		ItemStack icon() {
			if (icon == null) {
				ItemStack s = new ItemStack(blockType().asItem());
				icon = s.isEmpty() ? new ItemStack(Items.CHEST) : s;
			}
			return icon;
		}

		/** "Large Chest", "Barrel", "Red Shulker Box", or the name the player gave it in an anvil. */
		String displayName() {
			if (customName != null && !customName.isBlank()) return customName;
			if (blockName == null) {
				blockName = twin ? Component.translatable("container.chestDouble").getString() : blockType().getName().getString();
			}
			return blockName;
		}

		/** Slots per row of the container's grid, to describe where an item sits. */
		int rowWidth() {
			return switch (size) {
				case 5 -> 5;
				case 9 -> 3;
				default -> 9;
			};
		}

		int itemCount() {
			int n = 0;
			for (SlotStack s : slots) n += s.stack.getCount();
			return n;
		}
	}

	/** A trade as the player last saw it in the villager's trading screen. */
	record Offer(ItemStack costA, ItemStack costB, ItemStack result, int uses, int maxUses) {
		boolean outOfStock() {
			return uses >= maxUses;
		}
	}

	/** A villager whose trades the player opened. */
	static final class StoredVillager {
		final String uuid;
		String dim = "";
		int x, y, z;
		String profession = "minecraft:none";
		int level = 1;
		@Nullable String name;
		/** When the villager was last seen (trading screen or in view). */
		long seen;
		/** When its trades were last read. */
		long tradesSeen;
		List<Offer> offers = new ArrayList<>();

		StoredVillager(String uuid) {
			this.uuid = uuid;
		}

		String professionName() {
			Identifier id = Identifier.tryParse(profession);
			String path = id == null ? "none" : id.getPath();
			String ns = id == null ? "minecraft" : id.getNamespace();
			return Component.translatable("entity." + ns + ".villager." + path).getString();
		}

		String levelName() {
			return Component.translatable("merchant.level." + Math.max(1, Math.min(5, level))).getString();
		}

		String displayName() {
			String job = professionName();
			return name != null && !name.isBlank() ? name + " · " + job : job;
		}
	}
}
