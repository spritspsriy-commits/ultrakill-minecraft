package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.PlacedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every block set on the server, for Safe Building ({@link PlacedBlocks}): what a placement put down, and what's gone again. */
@Mixin(Level.class)
abstract class LevelMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
	private void ukpassthrough$setStarts(final BlockPos pos, final BlockState state, final int flags, final int recursionLeft, final CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerLevel level) {
			PlacedBlocks.setStarts(level);
		}
	}

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
	private void ukpassthrough$setEnds(final BlockPos pos, final BlockState state, final int flags, final int recursionLeft, final CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerLevel level) {
			PlacedBlocks.setEnds(level, pos, state, cir.getReturnValueZ());
		}
	}
}
