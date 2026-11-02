package com.bloomclient.client.core;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.api.event.DimensionChangeEvent;
import com.bloomclient.client.api.event.WorldJoinEvent;
import com.bloomclient.client.api.event.WorldLeaveEvent;
import com.bloomclient.client.api.storage.WorldIdentity;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Detects joining, leaving and changing dimension from the client's own
 * state once per tick (no extra hooks into networking), and identifies the
 * current world for per-world storage.
 */
public final class WorldSession {
	private @Nullable WorldIdentity current;
	private @Nullable ClientLevel lastLevel;
	private @Nullable Identifier lastDimension;
	private Vec3 lastPosition = Vec3.ZERO;

	public @Nullable WorldIdentity current() {
		return current;
	}

	public void tick(Minecraft mc, EventBus bus) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			if (level == null && current != null) {
				leave(bus);
			}
			return;
		}
		Identifier dimension = level.dimension().identifier();
		if (current == null) {
			current = identify(mc);
			lastLevel = level;
			lastDimension = dimension;
			lastPosition = mc.player.position();
			BloomClient.LOGGER.info("Bloom: entered world {}", current.key());
			bus.post(new WorldJoinEvent(current));
			return;
		}
		if (level != lastLevel) {
			WorldIdentity id = identify(mc);
			if (!id.key().equals(current.key())) {
				leave(bus);
				current = id;
				lastDimension = dimension;
				bus.post(new WorldJoinEvent(current));
			} else if (lastDimension != null && !dimension.equals(lastDimension)) {
				bus.post(new DimensionChangeEvent(current, lastDimension, dimension, lastPosition, mc.player.position()));
			}
			lastLevel = level;
			lastDimension = dimension;
		}
		lastPosition = mc.player.position();
	}

	private void leave(EventBus bus) {
		WorldIdentity left = current;
		current = null;
		lastLevel = null;
		lastDimension = null;
		if (left != null) {
			bus.post(new WorldLeaveEvent(left));
			BloomClient.LOGGER.info("Bloom: left world {}", left.key());
		}
	}

	/** Forces a leave event (game shutting down while in a world). */
	public void close(EventBus bus) {
		if (current != null) leave(bus);
	}

	static WorldIdentity identify(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path name = root.getFileName();
			String folder = name == null ? "world" : name.toString();
			return WorldIdentity.singleplayer(folder, server.getWorldData().getLevelName());
		}
		ServerData data = mc.getCurrentServer();
		if (data != null) {
			if (data.isRealm()) return WorldIdentity.realms(data.name);
			return WorldIdentity.multiplayer(data.ip, data.name);
		}
		return WorldIdentity.multiplayer("unknown", "?");
	}
}
