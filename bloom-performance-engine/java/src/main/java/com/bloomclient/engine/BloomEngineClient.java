package com.bloomclient.engine;

import com.bloomclient.engine.diagnostics.BloomDebugEntries;
import net.fabricmc.api.ClientModInitializer;

public final class BloomEngineClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BloomEngine.init();
		BloomDebugEntries.register();
	}
}
