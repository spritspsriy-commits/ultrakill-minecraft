package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks an orb's pickup while it runs (after Mending has repaired what it can, the rest goes to Player.giveExperiencePoints), so
 * Progression Mode can tell experience picked up from experience given any other way (commands, enchanting's cost). Server thread.
 */
@Mixin(ExperienceOrb.class)
abstract class ExperienceOrbMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"))
	private void ukpassthrough$pickupStart(final Player player, final CallbackInfo ci) {
		UKPassthrough.orbTouch = true;
	}

	@Inject(method = "playerTouch", at = @At("RETURN"))
	private void ukpassthrough$pickupEnd(final Player player, final CallbackInfo ci) {
		UKPassthrough.orbTouch = false;
	}
}
