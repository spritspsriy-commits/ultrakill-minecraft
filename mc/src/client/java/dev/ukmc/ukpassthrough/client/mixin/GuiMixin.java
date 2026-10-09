package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.Control;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft's world pauses while ULTRAKILL's own pause menu is up over its picture (round 25: the settings menu's "Open
 * ULTRAKILL Settings" opens it), as it does for its own pause menu: Minecraft.runTick pauses a single-player game whose
 * Gui says it's pausing.
 */
@Mixin(Gui.class)
abstract class GuiMixin {
	@Inject(method = "isPausing", at = @At("RETURN"), cancellable = true)
	private void ukpassthrough$pauseWithUltrakill(final CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && Control.ultrakillPaused()) {
			cir.setReturnValue(true);
		}
	}
}
