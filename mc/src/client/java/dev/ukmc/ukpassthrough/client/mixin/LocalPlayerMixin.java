package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.PlayerFollow;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Steve's body stays on V1's feet: set when the tick starts and again just before the position is sent. */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void ukpassthrough$followV1(final CallbackInfo ci) {
		PlayerFollow.apply((LocalPlayer) (Object) this);
	}

	@Inject(method = "sendPosition", at = @At("HEAD"))
	private void ukpassthrough$sendV1(final CallbackInfo ci) {
		PlayerFollow.apply((LocalPlayer) (Object) this);
	}
}
