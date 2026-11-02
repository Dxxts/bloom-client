package com.bloomclient.client.api.storage;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** Storage locations available to a mod. */
public interface ModStorage {
	/** {@code .bloom/mods/<mod>/}: data not tied to a world. */
	Path globalDir();

	/** Storage of the current world, or {@code null} outside a world. */
	@Nullable WorldStorage world();
}
