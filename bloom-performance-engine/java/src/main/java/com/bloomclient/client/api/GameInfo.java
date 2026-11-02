package com.bloomclient.client.api;

import com.bloomclient.client.api.storage.WorldIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Game state shared by Bloom's HUD widgets and mods, refreshed once per client
 * tick from data the client already has (the same values the F3 screen
 * shows). Values that change rarely are refreshed less often: biome when the
 * block position changes, ping and permissions once per second.
 *
 * <p>Read on the client thread only.
 */
public final class GameInfo {
	private boolean inWorld;
	private double x, y, z;
	private int blockX, blockY, blockZ;
	private float yaw, pitch;
	private Direction facing = Direction.NORTH;
	private @Nullable Identifier dimension;
	private @Nullable Identifier biome;
	private int fps;
	private int ping = -1;
	private boolean singleplayer;
	private boolean canUseCommands;
	private @Nullable WorldIdentity world;
	private long tick;
	private long biomeCheckedAt = Long.MIN_VALUE;
	private long biomeBlock = Long.MIN_VALUE;

	/** Internal: called by Bloom at the end of each client tick. */
	public void update(Minecraft mc, @Nullable WorldIdentity currentWorld) {
		tick++;
		fps = mc.getFps();
		world = currentWorld;
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			inWorld = false;
			dimension = null;
			biome = null;
			ping = -1;
			biomeBlock = Long.MIN_VALUE;
			return;
		}
		inWorld = true;
		x = player.getX();
		y = player.getY();
		z = player.getZ();
		BlockPos pos = player.blockPosition();
		blockX = pos.getX();
		blockY = pos.getY();
		blockZ = pos.getZ();
		yaw = player.getYRot();
		pitch = player.getXRot();
		facing = player.getDirection();
		dimension = mc.level.dimension().identifier();
		singleplayer = mc.hasSingleplayerServer();
		long packed = pos.asLong();
		if (packed != biomeBlock || tick - biomeCheckedAt >= 100) {
			biomeBlock = packed;
			biomeCheckedAt = tick;
			Holder<Biome> holder = mc.level.getBiome(pos);
			biome = holder.unwrapKey().map(k -> k.identifier()).orElse(null);
		}
		if (tick % 20 == 0 || ping == -1) {
			ping = -1;
			ClientPacketListener connection = mc.getConnection();
			if (connection != null && !singleplayer) {
				PlayerInfo info = connection.getPlayerInfo(player.getUUID());
				if (info != null) ping = info.getLatency();
			}
			canUseCommands = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		}
	}

	public boolean inWorld() {
		return inWorld;
	}

	public double x() {
		return x;
	}

	public double y() {
		return y;
	}

	public double z() {
		return z;
	}

	public int blockX() {
		return blockX;
	}

	public int blockY() {
		return blockY;
	}

	public int blockZ() {
		return blockZ;
	}

	/** Yaw in degrees as Minecraft reports it (0 = south, 90 = west). */
	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}

	public Direction facing() {
		return facing;
	}

	public @Nullable Identifier dimension() {
		return dimension;
	}

	public @Nullable Identifier biome() {
		return biome;
	}

	public int fps() {
		return fps;
	}

	/** Latency reported by the server in ms, or -1 when not available (singleplayer, not yet received). */
	public int ping() {
		return ping;
	}

	public boolean singleplayer() {
		return singleplayer;
	}

	/** Whether the player may use game-master commands (e.g. {@code /tp}) right now. */
	public boolean canUseCommands() {
		return canUseCommands;
	}

	public @Nullable WorldIdentity world() {
		return world;
	}

	public long tick() {
		return tick;
	}
}
