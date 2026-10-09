package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.MeleeWindups;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mobs whose melee is a doHurtTarget of their own, not Mob's (the golem's toss, the hoglin's throw, the bee's sting): their
 * hit on V1 winds up too, so it can be parried ({@link MeleeWindups}; round 16b). It lands through the same method.
 */
@Mixin({IronGolem.class, Hoglin.class, Zoglin.class, Bee.class})
abstract class OwnAttackMobsMixin {
	@Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$windUp(final ServerLevel level, final Entity target, final CallbackInfoReturnable<Boolean> cir) {
		if (MeleeWindups.holdBack((Mob) (Object) this, level, target)) {
			cir.setReturnValue(false);
		}
	}
}
