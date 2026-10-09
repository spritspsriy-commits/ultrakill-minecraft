package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Regeneration heals V1. Minecraft only heals someone below full health, and while V1 drives Steve stays at full health
 * (PlayerMixin): each of the effect's ticks (one every 2.5 s at level I, faster above) goes to V1 here instead.
 */
// the class isn't public
@Mixin(targets = "net.minecraft.world.effect.RegenerationMobEffect")
abstract class RegenerationMobEffectMixin {
	@Inject(method = "applyEffectTick", at = @At("HEAD"))
	private void ukpassthrough$regenerateV1(final ServerLevel level, final LivingEntity entity, final int amplifier, final CallbackInfoReturnable<Boolean> cir) {
		UKPassthrough.V1Heal heal = UKPassthrough.v1Heal;
		if (UKPassthrough.v1Driving && heal != null && entity instanceof ServerPlayer && entity.isAlive() && entity.getHealth() >= entity.getMaxHealth()) {
			heal.heal(1.0F);
		}
	}
}
