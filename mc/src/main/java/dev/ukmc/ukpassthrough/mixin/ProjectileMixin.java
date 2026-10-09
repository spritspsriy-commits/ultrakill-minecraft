package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A projectile V1 boosted (UKPassthrough.boosted) explodes where it hits something, as ULTRAKILL's boosted projectiles do (round
 * 20b): after Minecraft has dealt the hit (every projectile hits through hitTargetOrDeflectSelf), ULTRAKILL is asked for its
 * explosion there ({@link UKPassthrough#boostedHit}), and the projectile goes, as ULTRAKILL's does. Not an ender pearl: it takes
 * the player where it lands. Server thread.
 */
@Mixin(Projectile.class)
abstract class ProjectileMixin {
	@Inject(method = "hitTargetOrDeflectSelf", at = @At("RETURN"))
	private void ukpassthrough$boostedHit(final HitResult hit, final CallbackInfoReturnable<ProjectileDeflection> cir) {
		Projectile self = (Projectile) (Object) this;
		if (hit.getType() == HitResult.Type.MISS || self.level().isClientSide() || self instanceof ThrownEnderpearl || UKPassthrough.boosted.remove(self.getId()) == null) {
			return;
		}

		UKPassthrough.BoostedHit boostedHit = UKPassthrough.boostedHit;
		if (boostedHit != null) {
			boostedHit.hit(self, hit.getLocation());
		}

		if (!self.isRemoved()) {
			self.discard();
		}
	}
}
