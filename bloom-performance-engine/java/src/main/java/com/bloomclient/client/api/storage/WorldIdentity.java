package com.bloomclient.client.api.storage;

import com.bloomclient.client.core.BloomStorage;
import java.util.Locale;

/**
 * Identifies the world or server the player is in, so per-world data never
 * mixes between worlds.
 *
 * <ul>
 *   <li>singleplayer: the save folder name ({@code sp-<folder>}), stable across renames of the display name;</li>
 *   <li>multiplayer: the normalized server address ({@code mp-<host>_<port>});</li>
 *   <li>Realms: the realm name ({@code realms-<name>}).</li>
 * </ul>
 *
 * @param key         folder-safe unique key
 * @param kind        {@code singleplayer}, {@code multiplayer} or {@code realms}
 * @param displayName name to show to the player
 */
public record WorldIdentity(String key, String kind, String displayName) {
	public static WorldIdentity singleplayer(String folder, String displayName) {
		return new WorldIdentity("sp-" + BloomStorage.sanitize(folder), "singleplayer", displayName);
	}

	public static WorldIdentity multiplayer(String address, String displayName) {
		String a = address.trim().toLowerCase(Locale.ROOT);
		if (!a.contains(":") || a.startsWith("[")) {
			a = a + ":25565";
		}
		return new WorldIdentity("mp-" + BloomStorage.sanitize(a.replace(':', '_')), "multiplayer", displayName);
	}

	public static WorldIdentity realms(String name) {
		return new WorldIdentity("realms-" + BloomStorage.sanitize(name), "realms", name);
	}

	public boolean isSingleplayer() {
		return kind.equals("singleplayer");
	}
}
