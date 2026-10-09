package dev.ukmc.ukpassthrough.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** V1's hits come many times a second (nails, shotgun pellets): each one has to land, so the hurt cooldown is reset. */
@Mixin(Entity.class)
public interface EntityAccessor {
	@Accessor("invulnerableTime")
	void ukpassthrough$setInvulnerableTime(int ticks);
}
