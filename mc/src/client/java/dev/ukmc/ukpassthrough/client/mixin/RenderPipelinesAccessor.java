package dev.ukmc.ukpassthrough.client.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Minecraft's pipeline parts, for ULTRAKILL's picture pipeline (FrameOverlay) and the held item's mask (StainMask).
 * Private in Minecraft; Fabric API opens them too, but not when building against a local Fabric API jar.
 */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {
	@Accessor("GUI_TEXTURED_SNIPPET")
	static RenderPipeline.Snippet ukpassthrough$guiTexturedSnippet() {
		throw new AssertionError();
	}

	@Accessor("GLOBALS_SNIPPET")
	static RenderPipeline.Snippet ukpassthrough$globalsSnippet() {
		throw new AssertionError();
	}

	@Invoker("register")
	static RenderPipeline ukpassthrough$register(final RenderPipeline pipeline) {
		throw new AssertionError();
	}
}
