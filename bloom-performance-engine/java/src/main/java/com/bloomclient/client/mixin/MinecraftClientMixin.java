package com.bloomclient.client.mixin;

import com.bloomclient.client.BloomClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client tick and shutdown hooks for Bloom Client (the engine has its own frame hooks in MinecraftMixin). */
@Mixin(Minecraft.class)
abstract class MinecraftClientMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void bloom$tick(CallbackInfo ci) {
		BloomClient.tick((Minecraft) (Object) this);
	}

	@Inject(method = "stop", at = @At("HEAD"))
	private void bloom$stop(CallbackInfo ci) {
		BloomClient.shutdown();
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void bloom$close(CallbackInfo ci) {
		BloomClient.shutdown();
	}
}
