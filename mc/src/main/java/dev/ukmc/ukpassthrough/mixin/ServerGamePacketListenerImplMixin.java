package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * V1 dashes and slams far faster than a player walks: while it moves the player, the server's "moved too
 * quickly / moved wrongly" checks are off. Only while V1 drives; the world's player_movement_check game rule
 * stays as it is.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
	@Inject(method = "shouldCheckPlayerMovement", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$v1Moves(final boolean elytra, final CallbackInfoReturnable<Boolean> cir) {
		if (UKPassthrough.v1Driving) {
			cir.setReturnValue(false);
		}
	}
}
