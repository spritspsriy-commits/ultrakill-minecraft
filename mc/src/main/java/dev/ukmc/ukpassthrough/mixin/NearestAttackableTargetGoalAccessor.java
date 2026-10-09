package dev.ukmc.ukpassthrough.mixin;

import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which of a mob's target goals is the one for players: ULTRAKILL's enemies get theirs at its priority (UkEnemies). */
@Mixin(NearestAttackableTargetGoal.class)
public interface NearestAttackableTargetGoalAccessor {
	@Accessor("targetType")
	Class<?> ukpassthrough$targetType();
}
