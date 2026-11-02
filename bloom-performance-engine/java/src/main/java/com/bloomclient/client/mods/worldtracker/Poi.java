package com.bloomclient.client.mods.worldtracker;

import java.util.UUID;

/** A saved point of interest. Serialized as-is in {@code points.json}. */
public final class Poi {
	public String id = UUID.randomUUID().toString();
	public String name = "";
	public String description = "";
	public PoiCategory category = PoiCategory.CUSTOM;
	/** Dimension id, e.g. {@code minecraft:overworld}. */
	public String dimension = "minecraft:overworld";
	public int x, y, z;
	/** Creation time, epoch milliseconds. */
	public long created = System.currentTimeMillis();

	void sanitize() {
		if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
		if (name == null) name = "";
		if (description == null) description = "";
		if (category == null) category = PoiCategory.CUSTOM;
		if (dimension == null || dimension.isBlank()) dimension = "minecraft:overworld";
		if (name.length() > 64) name = name.substring(0, 64);
		if (description.length() > 256) description = description.substring(0, 256);
	}
}
