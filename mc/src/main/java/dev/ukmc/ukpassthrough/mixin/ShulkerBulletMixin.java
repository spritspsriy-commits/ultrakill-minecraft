package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A shulker bullet passes through anything without physics (ShulkerBullet.canHitEntity), and while V1 drives the player has none
 * ({@link PlayerMixin}): the bullets circled V1 and never hit (round 33, a player's report). V1 is really there, so for the player the
 * bullet asks only what every projectile asks (Projectile.canHitEntity: it can be hit by projectiles, and isn't riding with the bullet's
 * owner while the bullet is still leaving it). An @Inject, not a @Redirect of the field: the MixinExtras in the launcher installation's
 * Fabric Loader 0.19.3 crashes on a @Redirect (round 33's first build didn't start there).
 */
@Mixin(ShulkerBullet.class)
abstract class ShulkerBulletMixin {
	@Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$v1IsThere(final Entity target, final CallbackInfoReturnable<Boolean> cir) {
		if (!UKPassthrough.v1Driving || !(target instanceof Player player) || player.isSpectator()) {
			return;
		}

		ShulkerBullet self = (ShulkerBullet) (Object) this;
		Entity owner = self.getOwner();
		cir.setReturnValue(target.canBeHitByProjectile()
			&& (owner == null || ((ProjectileAccessor) self).ukpassthrough$leftOwner() || !owner.isPassengerOfSameVehicle(target)));
	}
}
