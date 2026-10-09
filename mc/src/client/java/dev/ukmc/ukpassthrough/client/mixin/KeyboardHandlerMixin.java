package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.Control;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keys go to ULTRAKILL while V1 has the controls (and F7 switches who has them). They still count as someone playing,
 * or Minecraft lowers its frame rate after a minute of only keys (its "inactivity" limit).
 */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
	@Shadow @Final private Minecraft minecraft;

	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$keyPress(final long window, final int action, final KeyEvent event, final CallbackInfo ci) {
		if (window == this.minecraft.getWindow().handle() && Control.onKey(event, action)) {
			this.minecraft.getFramerateLimitTracker().onInputReceived();
			ci.cancel();
		}
	}
}
