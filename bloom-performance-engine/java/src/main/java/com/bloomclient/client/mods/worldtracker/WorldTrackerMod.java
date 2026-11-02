package com.bloomclient.client.mods.worldtracker;

import com.bloomclient.client.api.BloomMod;
import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.api.ModCategory;
import com.bloomclient.client.api.ModContext;
import com.bloomclient.client.api.ModMetadata;
import com.bloomclient.client.api.event.ClientTickEvent;
import com.bloomclient.client.api.event.DimensionChangeEvent;
import com.bloomclient.client.api.event.WorldJoinEvent;
import com.bloomclient.client.api.event.WorldLeaveEvent;
import com.bloomclient.client.api.settings.Setting;
import com.bloomclient.client.api.settings.SettingsGroup;
import com.bloomclient.client.api.storage.WorldIdentity;
import com.bloomclient.client.api.storage.WorldStorage;
import com.bloomclient.client.mods.worldtracker.TrackerRecords.HistoryPoint;
import com.bloomclient.client.mods.worldtracker.TrackerRecords.Suggestion;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Tr;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelData;
import org.jspecify.annotations.Nullable;

/**
 * World Tracker: an organized memory of each world. Saved points of interest
 * with categories, exploration history (visited chunks, positions, trips,
 * biomes, time per dimension), suggestions from data the client already has
 * (villagers in view, the portal you just used, your last death, the world
 * spawn), HUD widgets and a page with list, exploration and map.
 *
 * <p>It never scans the world beyond the player's surroundings, never reads
 * server-side data and only offers teleporting in singleplayer when the
 * player is allowed to use commands.
 */
public final class WorldTrackerMod implements BloomMod {
	public static final String ID = "bloom-world-tracker";
	public static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";

	private static volatile @Nullable WorldTrackerMod active;
	private @Nullable ModContext ctx;
	@Nullable WorldState state;
	private @Nullable String lastBiome;
	private @Nullable HistoryPoint lastSample;

	Setting.Bool trackExploration, suggestVillages, suggestPortals, suggestDeath, suggestSpawn, convertNether, allowTeleport;
	Setting.Int exploreRadius, historyInterval, historyMinDistance, maxHistory;

	@Override
	public ModMetadata metadata() {
		return new ModMetadata(ID, "bloom.wt.name", "bloom.wt.description", "1.0.0", "Bloom Client", ModCategory.WORLD, Icon.PIN);
	}

	@Override
	public void onInitialize(ModContext context) {
		this.ctx = context;
		SettingsGroup s = context.settings();
		s.section("bloom.wt.settings.exploration");
		trackExploration = s.bool("trackExploration", true, "bloom.wt.settings.track").describe("bloom.wt.settings.track.desc");
		exploreRadius = s.integer("exploreRadius", 1, 0, 4, 1, " chunk", "bloom.wt.settings.radius").describe("bloom.wt.settings.radius.desc");
		historyInterval = s.integer("historyInterval", 5, 2, 30, 1, " s", "bloom.wt.settings.interval").describe("bloom.wt.settings.interval.desc");
		historyMinDistance = s.integer("historyMinDistance", 8, 1, 64, 1, " m", "bloom.wt.settings.min_distance");
		maxHistory = s.integer("maxHistory", 3000, 500, 10000, 500, "bloom.wt.settings.max_history").describe("bloom.wt.settings.max_history.desc");
		s.section("bloom.wt.settings.suggestions");
		suggestVillages = s.bool("suggestVillages", true, "bloom.wt.settings.villages").describe("bloom.wt.settings.villages.desc");
		suggestPortals = s.bool("suggestPortals", true, "bloom.wt.settings.portals").describe("bloom.wt.settings.portals.desc");
		suggestDeath = s.bool("suggestDeath", true, "bloom.wt.settings.death");
		suggestSpawn = s.bool("suggestSpawn", true, "bloom.wt.settings.spawn");
		s.section("bloom.wt.settings.navigation");
		convertNether = s.bool("convertNether", true, "bloom.wt.settings.convert").describe("bloom.wt.settings.convert.desc");
		allowTeleport = s.bool("allowTeleport", true, "bloom.wt.settings.teleport").describe("bloom.wt.settings.teleport.desc");

		context.registerWidget(new TrackerWidgets.Dimension(this));
		context.registerWidget(new TrackerWidgets.Target(this));
		context.registerWidget(new TrackerWidgets.Linked());
		context.registerWidget(new TrackerWidgets.Overview(this));
		context.registerPage(new WorldTrackerPage(this));
	}

	@Override
	public void onEnable(ModContext context) {
		active = this;
		context.on(WorldJoinEvent.class, e -> openWorld(e.world()));
		context.on(WorldLeaveEvent.class, e -> closeWorld());
		context.on(DimensionChangeEvent.class, this::onDimensionChange);
		context.on(ClientTickEvent.class, this::tick);
		WorldIdentity current = context.game().world();
		if (current != null) openWorld(current);
	}

	@Override
	public void onDisable(ModContext context) {
		if (active == this) active = null;
		closeWorld();
	}

	ModContext ctx() {
		return ctx;
	}

	/**
	 * For other Bloom mods: name of the saved point closest to a position in
	 * the same dimension, within {@code maxDistance} blocks, or null (also when
	 * the World Tracker is off or no world is open).
	 */
	public static @Nullable String nearestPointName(String dimension, int x, int y, int z, int maxDistance) {
		WorldTrackerMod mod = active;
		WorldState s = mod == null ? null : mod.state;
		if (s == null) return null;
		Poi best = null;
		long bestD = (long) maxDistance * maxDistance;
		for (Poi p : s.points) {
			if (!p.dimension.equals(dimension)) continue;
			long dx = p.x - x, dy = p.y - y, dz = p.z - z;
			long d = dx * dx + dy * dy + dz * dz;
			if (d <= bestD) {
				bestD = d;
				best = p;
			}
		}
		return best == null ? null : best.name;
	}

	private void openWorld(WorldIdentity world) {
		closeWorld();
		WorldStorage storage = ctx.storage().world();
		if (storage == null) return;
		WorldState s = new WorldState(storage);
		s.load();
		state = s;
		lastBiome = null;
		lastSample = s.history.peekLast();
	}

	private void closeWorld() {
		if (state != null) {
			state.save(true);
			state = null;
		}
	}

	// ---------------------------------------------------------------- recording

	private void tick(ClientTickEvent e) {
		WorldState s = state;
		GameInfo g = ctx.game();
		if (s == null || !g.inWorld() || g.dimension() == null) return;
		String dim = g.dimension().toString();
		long now = System.currentTimeMillis();
		if (e.every(20)) {
			s.tickDimension(dim, g.blockX(), g.blockY(), g.blockZ(), now, 1);
			if (trackExploration.on()) {
				int cx = g.blockX() >> 4, cz = g.blockZ() >> 4, r = exploreRadius.get();
				for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) s.markExplored(dim, cx + dx, cz + dz);
				Identifier biome = g.biome();
				if (biome != null && !biome.toString().equals(lastBiome)) {
					lastBiome = biome.toString();
					s.visitBiome(lastBiome, dim, g.blockX(), g.blockY(), g.blockZ(), now);
				}
			}
		}
		if (trackExploration.on() && e.every(historyInterval.get() * 20)) {
			HistoryPoint last = lastSample;
			int min = historyMinDistance.get();
			boolean moved = last == null || !last.dim.equals(dim)
					|| WorldState.dist2(last.x, last.z, g.blockX(), g.blockZ()) + (long) (last.y - g.blockY()) * (last.y - g.blockY()) >= (long) min * min;
			if (moved) {
				HistoryPoint p = new HistoryPoint(now, dim, g.blockX(), g.blockY(), g.blockZ());
				s.addHistory(p, maxHistory.get());
				lastSample = p;
			}
		}
		if (e.every(100)) suggestFromSurroundings(s, dim);
		if (e.every(600)) s.save(false);
	}

	private void suggestFromSurroundings(WorldState s, String dim) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) return;
		if (suggestVillages.on()) {
			// Villagers the client is already rendering within 64 blocks.
			int n = 0;
			double sx = 0, sy = 0, sz = 0;
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (entity instanceof AbstractVillager && entity.distanceToSqr(player) < 64 * 64) {
					n++;
					sx += entity.getX();
					sy += entity.getY();
					sz += entity.getZ();
				}
			}
			if (n >= 3) {
				int x = (int) Math.floor(sx / n), y = (int) Math.floor(sy / n), z = (int) Math.floor(sz / n);
				s.suggest(new Suggestion("village:" + dim + ":" + (x >> 6) + ":" + (z >> 6), PoiCategory.VILLAGE, Tr.t("bloom.wt.suggest.village"), dim, x, y, z,
						Tr.t("bloom.wt.suggest.village.reason", n)), 96);
			}
		}
		if (suggestDeath.on()) {
			GlobalPos death = player.getLastDeathLocation().orElse(null);
			if (death != null) {
				BlockPos p = death.pos();
				String d = death.dimension().identifier().toString();
				s.suggest(new Suggestion("death:" + d + ":" + p.asLong(), PoiCategory.CUSTOM, Tr.t("bloom.wt.suggest.death"), d, p.getX(), p.getY(), p.getZ(),
						Tr.t("bloom.wt.suggest.death.reason")), 4);
			}
		}
		if (suggestSpawn.on()) {
			LevelData.RespawnData spawn = mc.level.getRespawnData();
			if (spawn != null) {
				BlockPos p = spawn.pos();
				String d = spawn.dimension().identifier().toString();
				s.suggest(new Suggestion("spawn:" + d + ":" + p.asLong(), PoiCategory.HOME, Tr.t("bloom.wt.suggest.spawn"), d, p.getX(), p.getY(), p.getZ(),
						Tr.t("bloom.wt.suggest.spawn.reason")), 16);
			}
		}
	}

	private void onDimensionChange(DimensionChangeEvent e) {
		WorldState s = state;
		if (s == null || !suggestPortals.on()) return;
		String from = e.from().toString(), to = e.to().toString();
		boolean netherTrip = (from.equals(OVERWORLD) && to.equals(NETHER)) || (from.equals(NETHER) && to.equals(OVERWORLD));
		if (!netherTrip) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		// Only when the player is actually standing in a nether portal (not after /tp).
		BlockPos here = mc.player.blockPosition();
		boolean portal = mc.level.getBlockState(here).is(Blocks.NETHER_PORTAL) || mc.level.getBlockState(here.above()).is(Blocks.NETHER_PORTAL);
		if (!portal) return;
		String name = Tr.t("bloom.wt.suggest.portal", dimensionName(from));
		s.suggest(new Suggestion("portal:" + to + ":" + here.asLong(), PoiCategory.PORTAL, name, to, here.getX(), here.getY(), here.getZ(),
				Tr.t("bloom.wt.suggest.portal.reason")), 24);
		BlockPos back = BlockPos.containing(e.lastPosition());
		s.suggest(new Suggestion("portal:" + from + ":" + back.asLong(), PoiCategory.PORTAL, Tr.t("bloom.wt.suggest.portal", dimensionName(to)), from, back.getX(),
				back.getY(), back.getZ(), Tr.t("bloom.wt.suggest.portal.reason")), 24);
	}

	// ---------------------------------------------------------------- navigation helpers

	/**
	 * Vector from the player to {@code p} in the player's dimension (converting
	 * between Overworld and Nether coordinates when enabled), or null when the
	 * point is in an unrelated dimension.
	 */
	double @Nullable [] delta(Poi p, GameInfo g) {
		if (!g.inWorld() || g.dimension() == null) return null;
		String here = g.dimension().toString();
		double tx = p.x + 0.5, ty = p.y, tz = p.z + 0.5;
		if (!p.dimension.equals(here)) {
			if (!convertNether.on()) return null;
			if (p.dimension.equals(NETHER) && here.equals(OVERWORLD)) {
				tx *= 8;
				tz *= 8;
			} else if (p.dimension.equals(OVERWORLD) && here.equals(NETHER)) {
				tx /= 8;
				tz /= 8;
			} else {
				return null;
			}
		}
		return new double[] {tx - g.x(), ty - g.y(), tz - g.z()};
	}

	/** Arrow pointing to the target relative to where the player looks. */
	public static String arrow(double dx, double dz, float yaw) {
		double target = Math.toDegrees(Math.atan2(-dx, dz));
		double rel = ((target - yaw) % 360 + 540) % 360 - 180;
		int sector = (int) Math.round(rel / 45.0);
		return switch (sector) {
			case 0 -> "↑";
			case 1 -> "↗";
			case 2 -> "→";
			case 3 -> "↘";
			case -1 -> "↖";
			case -2 -> "←";
			case -3 -> "↙";
			default -> "↓";
		};
	}

	public static String formatDistance(double d) {
		return d >= 10000 ? String.format(Locale.ROOT, "%.1f km", d / 1000) : Math.round(d) + " m";
	}

	public static String dimensionName(String id) {
		String key = "bloom.dimension." + id.replace(':', '.');
		String t = Tr.lookup(key);
		if (t != null) return t;
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		path = path.replace('_', ' ');
		return path.isEmpty() ? id : Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}

	boolean canTeleport() {
		GameInfo g = ctx.game();
		return allowTeleport.on() && g.inWorld() && g.singleplayer() && g.canUseCommands();
	}

	/** Singleplayer only, with command permission: runs the vanilla /tp command like the player could. */
	boolean teleport(Poi p) {
		if (!canTeleport()) return false;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return false;
		player.connection.sendCommand(String.format(Locale.ROOT, "execute in %s run tp @s %d.5 %d %d.5", p.dimension, p.x, p.y, p.z));
		return true;
	}
}
