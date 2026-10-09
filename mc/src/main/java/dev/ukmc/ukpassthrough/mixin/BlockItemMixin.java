package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.PlacedBlocks;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player placing a block (V1's hand too: it places through Minecraft's own use), for Safe Building ({@link PlacedBlocks}). */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {
	@Inject(method = "place", at = @At("HEAD"))
	private void ukpassthrough$placeStarts(final BlockPlaceContext context, final CallbackInfoReturnable<InteractionResult> cir) {
		PlacedBlocks.placeStarts(context.getLevel(), context.getPlayer());
	}

	@Inject(method = "place", at = @At("RETURN"))
	private void ukpassthrough$placeEnds(final BlockPlaceContext context, final CallbackInfoReturnable<InteractionResult> cir) {
		PlacedBlocks.placeEnds(context.getLevel(), context.getPlayer());
	}
}
