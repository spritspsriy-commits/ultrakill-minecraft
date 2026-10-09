package dev.ukmc.ukpassthrough.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A parried projectile belongs to the player from then on, and starts right next to the player: like a freshly shot
 * one, it mustn't hit the player before it has flown clear.
 */
@Mixin(Projectile.class)
public interface ProjectileAccessor {
	@Accessor("leftOwner")
	void ukpassthrough$setLeftOwner(boolean left);
}
