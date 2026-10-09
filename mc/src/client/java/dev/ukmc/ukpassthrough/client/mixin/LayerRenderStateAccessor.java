package dev.ukmc.ukpassthrough.client.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** One layer of a resolved item model: its quads and the transform they're drawn with (HeldModel). */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface LayerRenderStateAccessor {
	@Accessor("quads")
	ItemQuads ukpassthrough$quads();

	@Accessor("localTransform")
	Matrix4f ukpassthrough$localTransform();
}
