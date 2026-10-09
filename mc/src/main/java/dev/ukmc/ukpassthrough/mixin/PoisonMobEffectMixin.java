package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.PoisonMobEffect;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Poison never kills in Minecraft (it stops at half a heart): the damage it does while this runs tells V1 the same. */
@Mixin(PoisonMobEffect.class)
abstract class PoisonMobEffectMixin {
	@Inject(method = "applyEffectTick", at = @At("HEAD"))
	private void ukpassthrough$poisonStarts(final ServerLevel level, final LivingEntity entity, final int amplifier, final CallbackInfoReturnable<Boolean> cir) {
		UKPassthrough.poisonTick = true;
	}

	@Inject(method = "applyEffectTick", at = @At("RETURN"))
	private void ukpassthrough$poisonEnds(final ServerLevel level, final LivingEntity entity, final int amplifier, final CallbackInfoReturnable<Boolean> cir) {
		UKPassthrough.poisonTick = false;
	}
}
