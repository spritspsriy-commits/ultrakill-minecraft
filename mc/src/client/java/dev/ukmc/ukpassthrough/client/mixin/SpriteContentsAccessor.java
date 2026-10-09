package dev.ukmc.ukpassthrough.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A texture's own pixels (all its animation frames), for the held block's look in ULTRAKILL (HeldModel). */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
	@Accessor("originalImage")
	NativeImage ukpassthrough$originalImage();
}
