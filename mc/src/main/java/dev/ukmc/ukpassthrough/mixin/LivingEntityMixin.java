package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While V1 drives, the player's healing (instant health, regeneration below full health) goes to V1, and so does the
 * knockback it takes: worked out as LivingEntity.knockback does (knockback resistance, netherite's, takes its share; a
 * hop of at most 0.4 up), but without Steve's own motion, which collects nonsense while V1 moves him.
 * <p>
 * A mob the player's own attack hurts while V1 drives (UKPassthrough.striking: the weapon in V1's hand) goes to ULTRAKILL,
 * with what it lost, for blood and style points there; so does one a projectile V1 boosted hurts (UKPassthrough.boosted,
 * round 20).
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	/** Its health and absorption before a hurt from the player's attack, or -1. */
	@Unique
	private float ukpassthrough$beforeStrike = -1.0F;
	/** Its health and absorption before a hurt from a projectile V1 boosted, or -1. */
	@Unique
	private float ukpassthrough$beforeBoost = -1.0F;

	@Inject(method = "hurtServer", at = @At("HEAD"))
	private void ukpassthrough$strikeStarts(final ServerLevel level, final DamageSource source, final float amount, final CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		Player striking = UKPassthrough.striking;
		this.ukpassthrough$beforeStrike = striking != null && self != striking && source.getEntity() == striking ? self.getHealth() + self.getAbsorptionAmount() : -1.0F;
		Integer until = source.getDirectEntity() == null ? null : UKPassthrough.boosted.get(source.getDirectEntity().getId());
		boolean boosted = until != null && level.getServer().getTickCount() <= until && !(self instanceof Player);
		this.ukpassthrough$beforeBoost = boosted ? self.getHealth() + self.getAbsorptionAmount() : -1.0F;
	}

	@Inject(method = "hurtServer", at = @At("RETURN"))
	private void ukpassthrough$strikeEnds(final ServerLevel level, final DamageSource source, final float amount, final CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		float boostedBefore = this.ukpassthrough$beforeBoost;
		this.ukpassthrough$beforeBoost = -1.0F;
		UKPassthrough.V1Boost boost = UKPassthrough.v1Boost;
		if (boostedBefore >= 0.0F && boost != null && cir.getReturnValue()) {
			boost.boosted(self, Math.max(0.0F, boostedBefore - self.getHealth() - self.getAbsorptionAmount()), self.isDeadOrDying());
		}

		float before = this.ukpassthrough$beforeStrike;
		this.ukpassthrough$beforeStrike = -1.0F;
		Player striking = UKPassthrough.striking;
		UKPassthrough.V1Strike strike = UKPassthrough.v1Strike;
		if (before < 0.0F || striking == null || strike == null || !cir.getReturnValue()) {
			return;
		}

		strike.struck(self, Math.max(0.0F, before - self.getHealth() - self.getAbsorptionAmount()), self.isDeadOrDying(), striking.getMainHandItem());
	}

	@Inject(method = "heal", at = @At("HEAD"))
	private void ukpassthrough$healV1(final float amount, final CallbackInfo ci) {
		UKPassthrough.V1Heal heal = UKPassthrough.v1Heal;
		if (UKPassthrough.v1Driving && amount > 0.0F && heal != null && (Object) this instanceof ServerPlayer player && player.isAlive()) {
			heal.heal(amount);
		}
	}

	@Inject(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V", at = @At("HEAD"))
	private void ukpassthrough$knockV1(final double strength, final double x, final double z, final DamageSource source, final float unused, final boolean alsoUnused, final CallbackInfo ci) {
		UKPassthrough.V1Push push = UKPassthrough.v1Push;
		if (!UKPassthrough.v1Driving || push == null || !((Object) this instanceof ServerPlayer player)) {
			return;
		}

		double s = strength * (1.0 - player.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
		double length = Math.sqrt(x * x + z * z);
		if (s <= 0.0 || length < 1.0E-5) {
			return;
		}

		// (x, z) points from the player towards what hit it; the push goes the other way
		push.push(-x / length * s, Math.min(0.4, s), -z / length * s, source == null ? "knockback" : "knockback (" + source.typeHolder().getRegisteredName() + ")");
	}
}
