package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.UkLink;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No pause menu when the window loses focus while ULTRAKILL is linked: tabbing over to ULTRAKILL mustn't stop the
 * world (or V1). Only at runtime; the "Pause on Lost Focus" setting itself isn't touched.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$keepRunning(final CallbackInfo ci) {
		UkLink link = UkLink.get();
		if (link != null && link.connected()) {
			ci.cancel();
		}
	}
}
