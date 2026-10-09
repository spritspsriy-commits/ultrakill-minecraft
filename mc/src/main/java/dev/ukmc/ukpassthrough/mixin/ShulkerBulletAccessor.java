package dev.ukmc.ukpassthrough.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A parried shulker bullet stops steering for its target and flies where V1 sent it (Projectiles). */
@Mixin(ShulkerBullet.class)
public interface ShulkerBulletAccessor {
	@Accessor("finalTarget")
	void ukpassthrough$setFinalTarget(EntityReference<Entity> target);
}
