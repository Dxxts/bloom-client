package com.bloomclient.engine.mixin;

import com.bloomclient.engine.access.BloomBlockState;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateBaseMixin implements BloomBlockState {
	@Unique
	private int bloom$id = -1;

	@Override
	public int bloom$stateId() {
		int id = bloom$id;
		if (id < 0) {
			// Benign race: every thread computes the same value.
			id = Block.BLOCK_STATE_REGISTRY.getId((BlockState) (Object) this);
			bloom$id = id;
		}
		return id;
	}
}
