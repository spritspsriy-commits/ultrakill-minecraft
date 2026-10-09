package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While V1 drives, the player has no physics ({@link PlayerMixin}), and Minecraft skips the blocks an entity without
 * physics is inside (that's how spectators pass through everything). V1 is really there, though: portals, end gateways,
 * lava, fire, cactus, magma, pressure plates and tripwires must still notice the player. Damage from them goes to V1.
 */
@Mixin(Entity.class)
abstract class EntityMixin {
	@Inject(method = "isAffectedByBlocks", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$v1InBlocks(final CallbackInfoReturnable<Boolean> cir) {
		if (UKPassthrough.v1Driving && (Object) this instanceof Player player && !player.isSpectator() && !player.isRemoved()) {
			cir.setReturnValue(true);
		}
	}
}
