package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.BlockStream;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every block change the client sees marks its section for resending to ULTRAKILL. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void ukpassthrough$blockChanged(final BlockPos pos, final BlockState oldState, final BlockState newState, final int flags, final CallbackInfo ci) {
		if (oldState != newState) {
			BlockStream.blockChanged(pos.getX(), pos.getY(), pos.getZ());
		}
	}

	@Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
	private void ukpassthrough$sectionChanged(final int sx, final int sy, final int sz, final CallbackInfo ci) {
		BlockStream.sectionChanged(sx, sy, sz);
	}
}
