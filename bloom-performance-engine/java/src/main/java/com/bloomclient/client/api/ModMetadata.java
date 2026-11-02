package com.bloomclient.client.api;

import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Tr;
import java.util.Objects;

/**
 * Static description of a Bloom mod. {@code name} and {@code description}
 * may be translation keys ({@code bloom.*}) or plain text.
 *
 * @param id          unique id, lower case, e.g. {@code bloom-world-tracker}
 * @param version     the mod's own version
 * @param author      author or team
 * @param icon        one of Bloom's icons
 */
public record ModMetadata(String id, String name, String description, String version, String author, ModCategory category, Icon icon) {
	public ModMetadata {
		Objects.requireNonNull(id);
		if (!id.matches("[a-z0-9][a-z0-9_.-]{1,63}")) {
			throw new IllegalArgumentException("Invalid Bloom mod id: " + id);
		}
	}

	public String displayName() {
		return Tr.handles(name) ? Tr.t(name) : name;
	}

	public String displayDescription() {
		return Tr.handles(description) ? Tr.t(description) : description;
	}
}
