package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.Portrait;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * V1 instead of Steve where the inventory (and the creative inventory's inventory tab, which calls the same method) draws the
 * player, while V1 drives ({@link Portrait}). Other mobs drawn this way (a horse in its inventory) stay as they are.
 */
@Mixin(InventoryScreen.class)
abstract class InventoryScreenMixin {
	@Inject(method = "extractEntityInInventoryFollowsMouse", at = @At("HEAD"), cancellable = true)
	private static void ukpassthrough$drawV1(final GuiGraphicsExtractor graphics, final int x0, final int y0, final int x1, final int y1, final int size, final float lift,
		final float mouseX, final float mouseY, final LivingEntity entity, final CallbackInfo ci) {
		if (Portrait.draw(graphics, x0, y0, x1, y1, size, lift, mouseX, mouseY, entity)) {
			ci.cancel();
		}
	}
}
