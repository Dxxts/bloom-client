package com.bloomclient.client.api.event;

import com.bloomclient.client.api.storage.WorldIdentity;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Posted when the player changes dimension inside the same world.
 * {@code lastPosition} is the last known position in the old dimension,
 * {@code newPosition} the first one in the new dimension.
 */
public record DimensionChangeEvent(WorldIdentity world, Identifier from, Identifier to, Vec3 lastPosition, Vec3 newPosition) {
}
