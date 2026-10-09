package dev.ukmc.ukpassthrough.client.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The terrain's layers one at a time (LevelRendererMixin: the solid layer, then the cut-out one, so the depth copy for the blood stains has
 * the ground without grass and flowers). renderGroup draws a whole group (solid and cut-out together) with these same arguments.
 */
@Mixin(ChunkSectionsToRender.class)
public interface ChunkSectionsToRenderInvoker {
	@Invoker("renderLayers")
	void ukpassthrough$renderLayers(ChunkSectionLayer[] layers, GpuSampler sampler, RenderPass pass, GpuTextureView atlas, GpuTextureView lightmap, RenderPipeline pipeline,
		RenderPipeline multiDrawPipeline);
}
