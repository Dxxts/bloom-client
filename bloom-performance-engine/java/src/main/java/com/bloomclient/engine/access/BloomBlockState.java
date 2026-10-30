package com.bloomclient.engine.access;

/** Implemented on {@code BlockState} by {@code BlockStateBaseMixin}. */
public interface BloomBlockState {
	/** Id in {@code Block.BLOCK_STATE_REGISTRY}, cached on the state. */
	int bloom$stateId();
}
