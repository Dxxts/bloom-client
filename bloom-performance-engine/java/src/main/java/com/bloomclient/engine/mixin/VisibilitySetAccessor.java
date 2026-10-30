package com.bloomclient.engine.mixin;

import java.util.BitSet;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VisibilitySet.class)
public interface VisibilitySetAccessor {
	@Accessor("data")
	BitSet bloom$data();
}
