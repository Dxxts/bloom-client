package com.bloomclient.engine.mixin;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Registers Bloom's F3 entries in vanilla's registry, next to the vanilla ones. */
@Mixin(DebugScreenEntries.class)
public interface DebugScreenEntriesInvoker {
	@Invoker("register")
	static Identifier bloom$register(Identifier id, DebugScreenEntry entry) {
		throw new AssertionError();
	}
}
