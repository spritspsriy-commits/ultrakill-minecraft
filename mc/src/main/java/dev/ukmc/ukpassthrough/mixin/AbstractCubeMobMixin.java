package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.MeleeWindups;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slimes and magma cubes hurt whoever touches them (dealDamage, every tick of contact): on V1 that winds up first, so it can be
 * parried ({@link MeleeWindups}; round 16b). Touches meanwhile are part of the same windup; it lands through dealDamage, which
 * checks its own reach and sight again.
 */
@Mixin(AbstractCubeMob.class)
abstract class AbstractCubeMobMixin {
	@Shadow
	protected abstract void dealDamage(LivingEntity target);

	@Inject(method = "dealDamage", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$windUp(final LivingEntity target, final CallbackInfo ci) {
		AbstractCubeMob self = (AbstractCubeMob) (Object) this;
		if (target instanceof ServerPlayer player && self.level() instanceof ServerLevel level && MeleeWindups.holdBack(self, level, player, () -> this.dealDamage(player))) {
			ci.cancel();
		}
	}
}
