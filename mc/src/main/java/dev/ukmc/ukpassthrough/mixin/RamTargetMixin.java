package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.MeleeWindups;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.RamTarget;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.goat.Goat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A goat's ram hurts and knocks back what it runs into, in the ram's tick: when that's V1, the impact winds up first, so it
 * can be parried ({@link MeleeWindups}; round 16b). Its ticks meanwhile are held (it keeps pushing on); it lands as the ram's
 * tick, which looks for what it touches again: a V1 that got away isn't hit, and the goat rams on.
 */
@Mixin(RamTarget.class)
abstract class RamTargetMixin {
	@Shadow
	@Final
	private TargetingConditions ramTargeting;

	@Shadow
	protected abstract void tick(ServerLevel level, Goat goat, long time);

	// what the ram's tick hurts: the first living thing the goat's box touches
	@Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/goat/Goat;J)V", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$windUp(final ServerLevel level, final Goat goat, final long time, final CallbackInfo ci) {
		List<LivingEntity> touched = level.getNearbyEntities(LivingEntity.class, this.ramTargeting, goat, goat.getBoundingBox());
		if (!touched.isEmpty() && touched.getFirst() instanceof ServerPlayer player
			&& MeleeWindups.holdBack(goat, level, player, () -> this.tick(level, goat, level.getGameTime()))) {
			ci.cancel();
		}
	}
}
