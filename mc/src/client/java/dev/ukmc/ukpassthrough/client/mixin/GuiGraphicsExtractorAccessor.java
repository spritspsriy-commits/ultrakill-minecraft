package dev.ukmc.ukpassthrough.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where the HUD's draws go, for ULTRAKILL's picture with its extra textures (FrameOverlay). Private in Minecraft; Fabric
 * API opens it too, but not when building against a local Fabric API jar.
 */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {
	@Accessor("guiRenderState")
	GuiRenderState ukpassthrough$guiRenderState();
}
