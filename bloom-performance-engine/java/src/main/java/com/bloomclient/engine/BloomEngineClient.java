package com.bloomclient.engine;

import net.fabricmc.api.ClientModInitializer;

public final class BloomEngineClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BloomEngine.init();
	}
}
