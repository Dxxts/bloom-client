package com.bloomclient.client.mods.basememory;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.api.BloomMod;
import com.bloomclient.client.api.hud.HudElement;
import com.bloomclient.client.hud.HudManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.api.ModCategory;
import com.bloomclient.client.api.ModContext;
import com.bloomclient.client.api.ModMetadata;
import com.bloomclient.client.api.event.ClientTickEvent;
import com.bloomclient.client.api.event.WorldJoinEvent;
import com.bloomclient.client.api.event.WorldLeaveEvent;
import com.bloomclient.client.api.settings.Setting;
import com.bloomclient.client.api.settings.SettingsGroup;
import com.bloomclient.client.api.storage.WorldStorage;
import com.bloomclient.client.mods.basememory.MemoryRecords.Offer;
import com.bloomclient.client.mods.basememory.MemoryRecords.SlotStack;
import com.bloomclient.client.mods.basememory.MemoryRecords.StoredContainer;
import com.bloomclient.client.mods.basememory.MemoryRecords.StoredVillager;
import com.bloomclient.client.mods.worldtracker.WorldTrackerMod;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Tr;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * Base Memory: remembers what the player saw. Opening a chest, barrel,
 * shulker box, ender chest, hopper or dispenser records its contents;
 * opening a villager's trades records its offers. The page then answers
 * "where are my diamonds?" and "who sells Mending?", and a guide (a column of
 * client-side particles, a HUD widget and highlighted slots) leads the player
 * there.
 *
 * <p>Fair by construction: it only stores what the game already showed the
 * player in a screen, never scans the world, never sends packets or clicks.
 */
public final class BaseMemoryMod implements BloomMod {
	public static final String ID = "bloom-base-memory";
	private static @Nullable BaseMemoryMod active;

	private @Nullable ModContext ctx;
	@Nullable MemoryData data;
	final MemorySearch search = new MemorySearch(this);

	Setting.Bool rememberContainers, rememberVillagers, searchInside, forgetMissing, particles, highlight, closeMenuOnGuide;

	// Screen being watched and what it shows.
	private @Nullable Screen watched;
	private @Nullable ContainerCapture containerCapture;
	private @Nullable VillagerCapture villagerCapture;

	@Nullable Guide guide;

	/** Where the player is being led. */
	static final class Guide {
		String dim = "";
		double x, y, z;
		String label = "";
		@Nullable Item item;
		@Nullable String containerKey;
		@Nullable String villager;
		/** The player opened the target: the guide ends when that screen closes. */
		boolean reached;
	}

	@Override
	public ModMetadata metadata() {
		return new ModMetadata(ID, "bloom.bm.name", "bloom.bm.description", "1.0.0", "Bloom Client", ModCategory.INVENTORY, Icon.CHEST);
	}

	static @Nullable BaseMemoryMod active() {
		return active;
	}

	@Override
	public void onInitialize(ModContext context) {
		this.ctx = context;
		SettingsGroup s = context.settings();
		s.section("bloom.bm.settings.memory");
		rememberContainers = s.bool("rememberContainers", true, "bloom.bm.settings.containers").describe("bloom.bm.settings.containers.desc");
		rememberVillagers = s.bool("rememberVillagers", true, "bloom.bm.settings.villagers").describe("bloom.bm.settings.villagers.desc");
		searchInside = s.bool("searchInside", true, "bloom.bm.settings.inside").describe("bloom.bm.settings.inside.desc");
		forgetMissing = s.bool("forgetMissing", true, "bloom.bm.settings.forget").describe("bloom.bm.settings.forget.desc");
		s.section("bloom.bm.settings.guide");
		particles = s.bool("particles", true, "bloom.bm.settings.particles").describe("bloom.bm.settings.particles.desc");
		highlight = s.bool("highlight", true, "bloom.bm.settings.highlight").describe("bloom.bm.settings.highlight.desc");
		closeMenuOnGuide = s.bool("closeMenuOnGuide", true, "bloom.bm.settings.close_menu").describe("bloom.bm.settings.close_menu.desc");
		searchInside.onChange(v -> search.invalidate());

		context.registerWidget(new GuideWidget(this));
		context.registerPage(new BaseMemoryPage(this));
	}

	@Override
	public void onEnable(ModContext context) {
		active = this;
		context.on(WorldJoinEvent.class, e -> openWorld());
		context.on(WorldLeaveEvent.class, e -> closeWorld());
		context.on(ClientTickEvent.class, this::tick);
		if (context.game().world() != null) openWorld();
	}

	@Override
	public void onDisable(ModContext context) {
		if (active == this) active = null;
		closeWorld();
	}

	ModContext ctx() {
		return ctx;
	}

	private void openWorld() {
		closeWorld();
		WorldStorage storage = ctx.storage().world();
		RegistryOps<JsonElement> ops = Stacks.ops();
		if (storage == null || ops == null) return;
		MemoryData d = new MemoryData(storage);
		d.load(ops);
		data = d;
	}

	private void closeWorld() {
		finishCaptures();
		watched = null;
		guide = null;
		if (data != null) {
			data.save(true);
			data = null;
		}
	}

	// ---------------------------------------------------------------- tick

	private void tick(ClientTickEvent e) {
		Minecraft mc = Minecraft.getInstance();
		MemoryData d = data;
		if (d == null || mc.level == null || mc.player == null) return;
		watchScreen(mc, d);
		if (containerCapture != null && e.every(5)) containerCapture.snapshot();
		if (e.every(40)) followVillagers(mc, d);
		if (e.every(100) && forgetMissing.on()) forgetMissing(mc, d);
		if (guide != null) guideTick(mc, e);
		if (e.every(600)) d.save(false);
	}

	// ---------------------------------------------------------------- recording

	private void watchScreen(Minecraft mc, MemoryData d) {
		Screen screen = mc.gui.screen();
		if (screen == watched) return;
		finishCaptures();
		watched = screen;
		if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
		AbstractContainerMenu menu = acs.getMenu();
		HitResult hit = mc.hitResult;
		String dim = mc.level.dimension().identifier().toString();
		if (menu instanceof MerchantMenu merchant) {
			if (rememberVillagers.on() && hit instanceof EntityHitResult eh && eh.getEntity() instanceof Villager villager) {
				villagerCapture = new VillagerCapture(merchant, villager, dim);
				arrived(null, villager.getStringUUID());
			}
			return;
		}
		boolean storage = menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof HopperMenu || menu instanceof DispenserMenu;
		if (!storage || !rememberContainers.on()) return;
		if (!(hit instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = bh.getBlockPos();
		BlockState state = mc.level.getBlockState(pos);
		if (!state.hasBlockEntity()) return;
		int slots = containerSlots(menu);
		BlockPos twin = null;
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE && slots == 54) {
			twin = ChestBlock.getConnectedBlockPos(pos, state);
		}
		int expected = expectedSize(state.getBlock(), twin != null);
		// The crosshair must be on the block that was opened: if the sizes disagree, the player looked away before the screen opened.
		if (expected > 0 && expected != slots) return;
		boolean ender = state.getBlock() instanceof EnderChestBlock;
		String key = ender ? StoredContainer.ENDER : StoredContainer.keyFor(dim, pos, twin);
		containerCapture = new ContainerCapture(menu, key, dim, pos, twin, state, customName(acs.getTitle()));
		containerCapture.snapshot();
		arrived(key, null);
	}

	private static int expectedSize(Block block, boolean twin) {
		if (block instanceof ChestBlock) return twin ? 54 : 27;
		if (block instanceof BarrelBlock || block instanceof ShulkerBoxBlock || block instanceof EnderChestBlock) return 27;
		if (block instanceof HopperBlock) return 5;
		if (block instanceof DispenserBlock) return 9;
		return -1;
	}

	private static int containerSlots(AbstractContainerMenu menu) {
		int n = 0;
		for (Slot s : menu.slots) if (!(s.container instanceof Inventory)) n++;
		return n;
	}

	/** The name given in an anvil, or null for the default "Chest", "Large Chest", "Barrel"… */
	private static @Nullable String customName(Component title) {
		if (title.getContents() instanceof TranslatableContents tc && tc.getKey().startsWith("container.")) return null;
		String s = title.getString();
		return s.isBlank() ? null : s;
	}

	private void finishCaptures() {
		if (containerCapture != null) {
			containerCapture.commit();
			containerCapture = null;
		}
		if (villagerCapture != null) {
			villagerCapture.commit();
			villagerCapture = null;
		}
		if (guide != null && guide.reached) guide = null;
	}

	/** Contents of an open container screen, copied every few ticks and stored when it closes. */
	private final class ContainerCapture {
		private final AbstractContainerMenu menu;
		private final String key, dim;
		private final BlockPos pos;
		private final @Nullable BlockPos twin;
		private final String block;
		private final @Nullable String customName;
		private List<SlotStack> last = List.of();
		private int size;
		private boolean received;

		ContainerCapture(AbstractContainerMenu menu, String key, String dim, BlockPos pos, @Nullable BlockPos twin, BlockState state, @Nullable String customName) {
			this.menu = menu;
			this.key = key;
			this.dim = dim;
			this.pos = pos.immutable();
			this.twin = twin == null ? null : twin.immutable();
			this.block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			this.customName = customName;
		}

		void snapshot() {
			// State id 0: the server has not sent the contents yet (multiplayer latency).
			if (menu.getStateId() == 0) return;
			received = true;
			List<SlotStack> out = new ArrayList<>();
			int i = 0;
			for (Slot s : menu.slots) {
				if (s.container instanceof Inventory) continue;
				ItemStack stack = s.getItem();
				if (!stack.isEmpty()) out.add(new SlotStack(i, stack.copy()));
				i++;
			}
			size = i;
			last = out;
		}

		void commit() {
			snapshot();
			MemoryData d = data;
			RegistryOps<JsonElement> ops = Stacks.ops();
			if (!received || d == null || ops == null) return;
			StoredContainer c = new StoredContainer(key);
			c.dim = dim;
			c.x = pos.getX();
			c.y = pos.getY();
			c.z = pos.getZ();
			if (twin != null) {
				c.twin = true;
				c.tx = twin.getX();
				c.ty = twin.getY();
				c.tz = twin.getZ();
			}
			c.block = block;
			c.customName = customName;
			c.seen = System.currentTimeMillis();
			c.size = size;
			c.slots = last;
			d.put(c, ops);
		}
	}

	/** Trades of an open villager screen; offers arrive from the server a moment after the screen opens. */
	private final class VillagerCapture {
		private final MerchantMenu menu;
		private final Villager villager;
		private final String dim;

		VillagerCapture(MerchantMenu menu, Villager villager, String dim) {
			this.menu = menu;
			this.villager = villager;
			this.dim = dim;
		}

		void commit() {
			MemoryData d = data;
			if (d == null || menu.getOffers().isEmpty()) return;
			StoredVillager v = new StoredVillager(villager.getStringUUID());
			v.dim = dim;
			BlockPos p = villager.blockPosition();
			v.x = p.getX();
			v.y = p.getY();
			v.z = p.getZ();
			v.profession = villager.getVillagerData().profession().unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:none");
			v.level = menu.getTraderLevel() > 0 ? menu.getTraderLevel() : villager.getVillagerData().level();
			Component custom = villager.getCustomName();
			v.name = custom == null ? null : custom.getString();
			v.seen = v.tradesSeen = System.currentTimeMillis();
			for (MerchantOffer o : menu.getOffers()) {
				v.offers.add(new Offer(o.getCostA().copy(), o.getCostB().copy(), o.getResult().copy(), o.getUses(), o.getMaxUses()));
			}
			d.putVillager(v);
		}
	}

	/** Keeps the position (and job) of remembered villagers up to date while the client renders them. */
	private void followVillagers(Minecraft mc, MemoryData d) {
		if (d.villagers.isEmpty()) return;
		String dim = mc.level.dimension().identifier().toString();
		long now = System.currentTimeMillis();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof Villager villager)) continue;
			StoredVillager v = d.villagers.get(villager.getStringUUID());
			if (v == null) continue;
			BlockPos p = villager.blockPosition();
			boolean moved = !v.dim.equals(dim) || Math.abs(v.x - p.getX()) + Math.abs(v.y - p.getY()) + Math.abs(v.z - p.getZ()) > 2;
			if (moved) {
				v.dim = dim;
				v.x = p.getX();
				v.y = p.getY();
				v.z = p.getZ();
			}
			String profession = villager.getVillagerData().profession().unwrapKey().map(k -> k.identifier().toString()).orElse(v.profession);
			if (!profession.equals(v.profession)) {
				// A villager that changed job has different trades: the old ones would be wrong.
				v.profession = profession;
				v.offers.clear();
				d.putVillager(v);
			}
			v.seen = now;
			if (moved) d.villagerMoved();
			Guide g = guide;
			if (g != null && v.uuid.equals(g.villager)) {
				g.dim = dim;
				g.x = villager.getX();
				g.y = villager.getY() + villager.getBbHeight();
				g.z = villager.getZ();
			}
		}
	}

	/** Forgets containers that are no longer there, but only where the client actually has the blocks loaded. */
	private void forgetMissing(Minecraft mc, MemoryData d) {
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		String dim = level.dimension().identifier().toString();
		List<String> gone = null;
		for (StoredContainer c : d.containers.values()) {
			if (c.ender() || !c.dim.equals(dim)) continue;
			BlockPos pos = new BlockPos(c.x, c.y, c.z);
			if (player.blockPosition().distSqr(pos) > 48 * 48 || !level.isLoaded(pos)) continue;
			BlockState state = level.getBlockState(pos);
			if (!state.hasBlockEntity()) {
				if (gone == null) gone = new ArrayList<>();
				gone.add(c.key);
			}
		}
		if (gone != null) for (String key : gone) d.removeContainer(key);
	}

	// ---------------------------------------------------------------- guide

	void guideTo(StoredContainer c, @Nullable Item item) {
		Guide g = new Guide();
		g.dim = c.dim;
		g.x = c.twin ? (c.x + c.tx) / 2.0 + 0.5 : c.x + 0.5;
		g.y = c.y + 1;
		g.z = c.twin ? (c.z + c.tz) / 2.0 + 0.5 : c.z + 0.5;
		g.label = placeName(c);
		g.item = item;
		g.containerKey = c.key;
		startGuide(g);
	}

	void guideTo(StoredVillager v, @Nullable Item item) {
		Guide g = new Guide();
		g.dim = v.dim;
		g.x = v.x + 0.5;
		g.y = v.y + 2;
		g.z = v.z + 0.5;
		g.label = v.displayName();
		g.item = item;
		g.villager = v.uuid;
		startGuide(g);
	}

	private void startGuide(Guide g) {
		guide = g;
		ensureGuideWidget();
		Minecraft mc = Minecraft.getInstance();
		if (closeMenuOnGuide.on() && mc.level != null) mc.gui.setScreen(null);
		double d = distanceTo(g.dim, g.x, g.y, g.z);
		ctx.notify(d == Double.MAX_VALUE ? Tr.t("bloom.bm.guide.started_far", g.label, WorldTrackerMod.dimensionName(g.dim))
				: Tr.t("bloom.bm.guide.started", g.label, WorldTrackerMod.formatDistance(d)));
	}

	void stopGuide() {
		guide = null;
	}

	/**
	 * Layouts made before Base Memory existed have no guide line: it is added
	 * once, the first time a guide starts. If the player later removes it from
	 * the HUD Studio, it stays removed.
	 */
	private void ensureGuideWidget() {
		Path flag = ctx.storage().globalDir().resolve("guide-widget-added");
		if (Files.exists(flag)) return;
		HudManager hud = BloomClient.hud();
		boolean present = false;
		for (HudElement e : hud.elements()) present |= GuideWidget.ID.equals(e.widget);
		if (!present) {
			hud.editableElements().add(hud.create(GuideWidget.ID));
			hud.markDirty();
		}
		try {
			Files.createDirectories(flag.getParent());
			Files.writeString(flag, "1");
		} catch (IOException ignored) {
			// Worst case the line is offered again next session.
		}
	}

	/** The player opened something: if it is the guide's target, the guide is done once the screen closes. */
	private void arrived(@Nullable String containerKey, @Nullable String villager) {
		Guide g = guide;
		if (g == null) return;
		boolean hit = (containerKey != null && containerKey.equals(g.containerKey)) || (villager != null && villager.equals(g.villager));
		if (hit) {
			g.reached = true;
			ctx.notify(Tr.t("bloom.bm.guide.found", g.label));
		}
	}

	/** A column of light above the target, drawn with client-side particles only (nothing is sent to the server). */
	private void guideTick(Minecraft mc, ClientTickEvent e) {
		Guide g = guide;
		if (g == null || g.reached || !particles.on() || !e.every(2)) return;
		ClientLevel level = mc.level;
		if (!level.dimension().identifier().toString().equals(g.dim)) return;
		double dx = g.x - mc.player.getX(), dz = g.z - mc.player.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist > 512 || !level.isLoaded(BlockPos.containing(g.x, g.y, g.z))) return;
		var random = level.getRandom();
		// The column: taller when far away, so it can be seen over buildings.
		double height = dist > 24 ? 24 : 8;
		for (int i = 0; i < 2; i++) {
			double y = g.y + random.nextDouble() * height;
			level.addAlwaysVisibleParticle(ParticleTypes.END_ROD, true, g.x + (random.nextDouble() - 0.5) * 0.15, y, g.z + (random.nextDouble() - 0.5) * 0.15, 0,
					0.02, 0);
		}
		if (dist < 6) {
			// Close by: a soft ring around the target.
			double a = random.nextDouble() * Math.PI * 2;
			level.addAlwaysVisibleParticle(ParticleTypes.GLOW, true, g.x + Math.cos(a) * 0.7, g.y - 0.4, g.z + Math.sin(a) * 0.7, 0, 0.01, 0);
		}
	}

	// ---------------------------------------------------------------- helpers

	/** Horizontal distance from the player, or {@link Double#MAX_VALUE} in another dimension or outside a world. */
	double distanceTo(String dim, double x, double y, double z) {
		GameInfo g = ctx.game();
		if (!g.inWorld() || g.dimension() == null || !g.dimension().toString().equals(dim)) return Double.MAX_VALUE;
		double dx = x + 0.5 - g.x(), dz = z + 0.5 - g.z();
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** "Large Chest · Base" when a World Tracker point is near, else "Large Chest · 120 64 -30". */
	String placeName(StoredContainer c) {
		if (c.ender()) return c.displayName();
		String area = WorldTrackerMod.nearestPointName(c.dim, c.x, c.y, c.z, 48);
		return c.displayName() + " · " + (area != null ? area : c.x + " " + c.y + " " + c.z);
	}

	/** "Row 2, column 4" / "Slot 3" / "inside Red Shulker Box". */
	static String slotHint(StoredContainer c, List<Integer> slots, @Nullable ItemStack holder) {
		if (holder != null) return Tr.t("bloom.bm.inside", holder.getHoverName().getString());
		if (slots.isEmpty()) return "";
		if (slots.size() > 1) return Tr.t("bloom.bm.stacks", slots.size());
		int s = slots.get(0), w = c.rowWidth();
		if (c.size <= 5) return Tr.t("bloom.bm.slot", s + 1);
		return Tr.t("bloom.bm.row_col", s / w + 1, s % w + 1);
	}

	static String ago(long time) {
		if (time <= 0) return "";
		long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
		if (s < 60) return Tr.t("bloom.bm.ago.now");
		if (s < 3600) return Tr.t("bloom.bm.ago.minutes", s / 60);
		if (s < 86400) return Tr.t("bloom.bm.ago.hours", s / 3600);
		return Tr.t("bloom.bm.ago.days", s / 86400);
	}

	int containerCount() {
		return data == null ? 0 : data.containers.size();
	}

	/** Used by the slot highlight: the item to show in open containers, if a guide is active. */
	@Nullable Item highlightItem() {
		Guide g = guide;
		return g == null || !highlight.on() ? null : g.item;
	}

	/** Development preview only (see DevPreview): guides to the first remembered container that has a position. */
	public static void previewGuide() {
		BaseMemoryMod mod = active;
		MemoryData d = mod == null ? null : mod.data;
		if (d == null) return;
		for (StoredContainer c : d.containers.values()) {
			if (c.ender() || c.slots.isEmpty()) continue;
			mod.guideTo(c, c.slots.get(0).stack().getItem());
			return;
		}
	}

	/** Forgets everything remembered in this world. */
	void forgetAll() {
		MemoryData d = data;
		if (d == null) return;
		d.clearAll();
		guide = null;
	}
}
