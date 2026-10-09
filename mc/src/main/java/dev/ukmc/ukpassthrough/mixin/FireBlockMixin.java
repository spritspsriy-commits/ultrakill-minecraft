package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.PlacedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Safe Building and gasoline's fire ({@link PlacedBlocks}; round 30, the user's choice): while a fire that burning gasoline started
 * (or that spread from one) ticks, the blocks Safe Building keeps don't burn away, and don't count as its fuel either, so it doesn't
 * spread towards them and dies down beside them as beside stone. Other fire is Minecraft's as ever.
 */
@Mixin(FireBlock.class)
abstract class FireBlockMixin {
	@Shadow
	protected abstract boolean canBurn(BlockState state);

	@Shadow
	private int getIgniteOdds(final BlockState state) {
		throw new AssertionError();
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void ukpassthrough$tickStarts(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci) {
		PlacedBlocks.fireTick(level, pos, true);
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void ukpassthrough$tickEnds(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci) {
		PlacedBlocks.fireTick(level, pos, false);
	}

	@Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$keepPlaced(final Level level, final BlockPos pos, final int chance, final RandomSource random, final int age, final CallbackInfo ci) {
		if (PlacedBlocks.keptFromFire(level, pos)) {
			ci.cancel();
		}
	}

	@Inject(method = "isValidFireLocation", at = @At("RETURN"), cancellable = true)
	private void ukpassthrough$placedIsNoFuel(final BlockGetter level, final BlockPos pos, final CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && PlacedBlocks.gasFireTicking()) {
			cir.setReturnValue(PlacedBlocks.fuelBeside(level, pos, this::canBurn));
		}
	}

	@Inject(method = "getIgniteOdds(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"), cancellable = true)
	private void ukpassthrough$placedDoesNotCatch(final LevelReader level, final BlockPos pos, final CallbackInfoReturnable<Integer> cir) {
		if (cir.getReturnValueI() <= 0 || !PlacedBlocks.gasFireTicking()) {
			return;
		}

		// as FireBlock works it out, without the blocks kept from this fire
		int odds = 0;
		for (Direction d : Direction.values()) {
			BlockPos next = pos.relative(d);
			if (!PlacedBlocks.keptFromFire(level, next)) {
				odds = Math.max(odds, this.getIgniteOdds(level.getBlockState(next)));
			}
		}

		cir.setReturnValue(odds);
	}
}
