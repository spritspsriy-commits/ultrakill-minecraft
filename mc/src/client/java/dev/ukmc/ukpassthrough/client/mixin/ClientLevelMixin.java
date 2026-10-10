package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.BlockStream;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change the client sees marks its section for resending to ULTRAKILL. (Until round 32 a light update did too, through
 * setSectionDirtyWithNeighbors: in 26.3 only the server's light packets call it, and light isn't collision, so each one sent a section
 * that hadn't changed, and ULTRAKILL built it and its neighbours again.)
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void ukpassthrough$blockChanged(final BlockPos pos, final BlockState oldState, final BlockState newState, final int flags, final CallbackInfo ci) {
		if (oldState != newState) {
			BlockStream.blockChanged(pos.getX(), pos.getY(), pos.getZ());
		}
	}
}
