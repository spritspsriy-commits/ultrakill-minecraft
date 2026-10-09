package dev.ukmc.ukpassthrough.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Hostile mobs get a goal for ULTRAKILL's enemies beside the one for the player (UkEnemies). */
@Mixin(Mob.class)
public interface MobAccessor {
	@Accessor("targetSelector")
	GoalSelector ukpassthrough$targetSelector();
}
