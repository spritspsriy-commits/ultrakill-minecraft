package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.Knocked;
import dev.ukmc.ukpassthrough.MeleeWindups;
import dev.ukmc.ukpassthrough.MobReach;
import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mobs' melee against V1 (round 16): whether a mob reaches the player is measured from V1's newest position, with V1's shorter
 * reach ({@link MobReach}), and a hit on the player winds up before it lands ({@link MeleeWindups}). Only while V1 drives.
 * A mob the Whiplash pulls is stunned (round 33, {@link Knocked}): its AI waits (goals, brain, path, steering), Minecraft's physics
 * still move it.
 */
@Mixin(Mob.class)
abstract class MobMixin {
	@Shadow
	protected abstract AABB getAttackBoundingBox(double reach);

	// as Mob.isWithinMeleeAttackRange, but against V1's box and with its reach
	@Inject(method = "isWithinMeleeAttackRange", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$reachV1(final LivingEntity target, final CallbackInfoReturnable<Boolean> cir) {
		if (!UKPassthrough.v1Driving || !(target instanceof ServerPlayer player)) {
			return;
		}

		AABB v1 = MobReach.v1Box(player);
		if (v1 == null) {
			return;
		}

		Mob self = (Mob) (Object) this;
		AttackRange range = self.getActiveItem().get(DataComponents.ATTACK_RANGE);
		double min = range == null ? 0.0 : range.effectiveMinRange(self);
		double max = Math.max(min, (range == null ? MobReach.REACH : range.effectiveMaxRange(self)) * MobReach.v1Share);
		cir.setReturnValue(this.getAttackBoundingBox(max).intersects(v1) && (min <= 0.0 || !this.getAttackBoundingBox(min).intersects(v1)));
	}

	@Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$stunned(final CallbackInfo ci) {
		if (Knocked.stunned((Mob) (Object) this)) {
			ci.cancel();
		}
	}

	@Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$windUp(final ServerLevel level, final Entity target, final CallbackInfoReturnable<Boolean> cir) {
		if (MeleeWindups.holdBack((Mob) (Object) this, level, target)) {
			cir.setReturnValue(false);
		}
	}
}
