package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.Control;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No pop-ups (advancements, recipes, tutorial tips) while V1 plays: at a big GUI scale they cover ULTRAKILL's
 * style meter, and the "open your inventory" tip never goes away because E belongs to ULTRAKILL. They're only
 * hidden, so they show again as Steve.
 */
@Mixin(ToastManager.class)
abstract class ToastManagerMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$hideWhileV1(final GuiGraphicsExtractor graphics, final CallbackInfo ci) {
		if (Control.driving()) {
			ci.cancel();
		}
	}
}
