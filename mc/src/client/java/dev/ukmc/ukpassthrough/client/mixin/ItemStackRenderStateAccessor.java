package dev.ukmc.ukpassthrough.client.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A resolved item model's layers, for the held block's look in ULTRAKILL (HeldModel). */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
	@Accessor("layers")
	ItemStackRenderState.LayerRenderState[] ukpassthrough$layers();

	@Accessor("activeLayerCount")
	int ukpassthrough$activeLayerCount();
}
