package dev.ukmc.ukpassthrough.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.ukmc.ukpassthrough.client.StainMask;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The depth copies for {@link StainMask}. Minecraft draws the solid terrain and the mobs in one render pass, and
 * nothing can be copied in the middle of a pass, so while V1 drives the solid terrain gets a pass of its own just
 * before (same target, same pipelines, nothing cleared), its depth is copied, and the main pass skips it.
 * <p>
 * Round 29c (the user's "blood renders over tall grass and stuff"): that pass draws only the solid layer, the copy is taken, and the
 * cut-out layer (grass, flowers, crops, leaves, glass: textures with holes) follows in a pass of its own, with a copy of its own. So the
 * stain pass can tell a blade of grass from the ground behind it: since round 29d a blade standing in blood takes it, one far in front of
 * the blood doesn't ({@link StainMask#compose}).
 * <p>
 * No @Redirect here: the MixinExtras in Fabric Loader 0.19.3 (the launcher installation's) crashes on one in this
 * class (ClassCastException in FactoryRedirectWrapperMixinTransformer).
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Shadow
	@Final
	private GameRenderer gameRenderer;
	@Shadow
	@Final
	private TextureManager textureManager;
	@Shadow
	@Final
	private LevelRenderState levelRenderState;
	@Shadow
	private GpuSampler chunkLayerSampler;

	// the main pass's body: its sampler, fog and lighting are set up by now, its render pass not yet open
	// (LOCATION_BLOCKS is deprecated, but it's what executeSolid itself draws the terrain with)
	@SuppressWarnings("deprecation")
	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Lighting;setupFor(Lcom/mojang/blaze3d/platform/Lighting$Entry;)V", shift = At.Shift.AFTER))
	private void ukpassthrough$terrainFirst(final GpuBufferSlice fog, final boolean a, final ChunkSectionsToRender chunks, final FeatureRenderDispatcher.PreparedFrame frame,
		final boolean b, final boolean c, final CallbackInfo ci) {
		StainMask.beginFrame();
		if (!StainMask.active()) {
			return;
		}

		RenderTarget main = this.gameRenderer.mainRenderTarget();
		GpuTextureView atlas = this.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		GpuTextureView lightmap = this.gameRenderer.lightmap();
		boolean wire = this.levelRenderState.renderWireframeTerrain;
		// as ChunkSectionsToRender.renderGroup draws the opaque group, one layer at a time: the solid one, the copy, then the cut-out one
		ChunkSectionsToRenderInvoker layers = (ChunkSectionsToRenderInvoker) chunks;
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "UKPassthrough solid terrain", main.getColorTextureView(), Optional.empty(),
			main.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			layers.ukpassthrough$renderLayers(StainMask.groundOnly() ? new ChunkSectionLayer[] {ChunkSectionLayer.SOLID} : new ChunkSectionLayer[] {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT},
				this.chunkLayerSampler, pass, atlas, lightmap, wire ? RenderPipelines.WIREFRAME : null, wire ? RenderPipelines.WIREFRAME_MULTIDRAW : null);
		}

		StainMask.terrainDrawn(main);
		if (!StainMask.groundOnly()) {
			// as before round 29c: the cut-out terrain counts as the ground
			StainMask.cutoutDrawn(main);
			return;
		}

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "UKPassthrough cut-out terrain", main.getColorTextureView(), Optional.empty(),
			main.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			layers.ukpassthrough$renderLayers(new ChunkSectionLayer[] {ChunkSectionLayer.CUTOUT}, this.chunkLayerSampler, pass, atlas, lightmap,
				wire ? RenderPipelines.WIREFRAME : null, wire ? RenderPipelines.WIREFRAME_MULTIDRAW : null);
		}

		StainMask.cutoutDrawn(main);
	}

	// the terrain is drawn already this frame: the rest of executeSolid (the solid features: mobs, block entities)
	@Inject(method = "executeSolid", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$terrainOnce(final ChunkSectionsToRender chunks, final FeatureRenderDispatcher.PreparedFrame frame, final RenderPass pass, final CallbackInfo ci) {
		if (StainMask.takeTerrainDrawn()) {
			ProfilerFiller profiler = Profiler.get();
			profiler.push("renderSolidFeatures");
			frame.executeSolid(pass);
			profiler.pop();
			ci.cancel();
		}
	}

	// the main pass is closed; the always-on-top pass after the outline clears depth
	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;executeOutline(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
	private void ukpassthrough$sceneDrawn(final GpuBufferSlice fog, final boolean a, final ChunkSectionsToRender chunks, final FeatureRenderDispatcher.PreparedFrame frame,
		final boolean b, final boolean c, final CallbackInfo ci) {
		StainMask.sceneDrawn(this.gameRenderer.mainRenderTarget());
	}
}
