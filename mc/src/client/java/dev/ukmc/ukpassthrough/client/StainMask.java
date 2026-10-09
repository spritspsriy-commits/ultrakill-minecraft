package dev.ukmc.ukpassthrough.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.ukmc.ukpassthrough.UKPassthrough;
import dev.ukmc.ukpassthrough.client.mixin.RenderPipelinesAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

/**
 * Where something stands in front of Minecraft's terrain, so ULTRAKILL's blood stains stay behind
 * mobs. The stains are decals on ULTRAKILL's hidden blocks, ULTRAKILL only knows a mob as a box, and the picture
 * lies over all of Minecraft's frame. So while V1 drives, the main depth buffer is copied twice a frame: once with
 * only the solid terrain drawn (LevelRendererMixin draws it in a pass of its own just before the main pass, which
 * then skips it), and once after the main pass, with the mobs. Where the two differ, something was drawn in front of
 * the block a stain lies on, and ultrakill_picture.fsh leaves the stain out there. Render thread only.
 * <p>
 * The item V1 holds that Minecraft draws (a sword, a pickaxe: {@link V1Hand#drawsItem}) is drawn after both, against a
 * depth of its own, cleared first, so it can't sink into a wall in front of V1. Once it's drawn, its depth goes into the
 * copy with the mobs wherever it was drawn ({@link #heldItemDrawn}), so the blood stays off it too.
 * <p>
 * Round 29c and 29d (the user's reports: "blood renders over tall grass", then "make it so the grass and flowers is able to get blood
 * stains on it"): the terrain copy is the solid layer only, and a third copy is taken after the cut-out layer (grass, flowers, crops).
 * Before the stain pass, {@link #compose} works out how much blood each pixel takes from the three (stain_keep.fsh): none where a mob
 * stands in front, all on the ground, and on a plant as much as it stands close to the ground behind it, whose blood ULTRAKILL's
 * stain plane has there (ULTRAKILL doesn't have the plants).
 */
public final class StainMask {
	/** Copied into, sampled by the picture's shader, cleared when made (two equal copies mask nothing). */
	private static final int USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
	/**
	 * The held item's depth (InSampler; Minecraft's own full-screen triangle) written over the copy with the mobs where the
	 * item is, and nowhere else (held_item_mask.fsh drops the rest).
	 */
	/** How much blood each pixel takes, from the three depth copies (stain_keep.fsh), into {@link Copies#keep}. */
	private static final RenderPipeline KEEP = RenderPipelinesAccessor.ukpassthrough$register(RenderPipeline.builder(RenderPipelinesAccessor.ukpassthrough$globalsSnippet())
		.withLocation(Identifier.fromNamespaceAndPath(UKPassthrough.ID, "pipeline/stain_keep"))
		.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(UKPassthrough.ID, "core/stain_keep"))
		.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withColorTargetState(ColorTargetState.DEFAULT)
		.build());
	private static final RenderPipeline HELD_ITEM = RenderPipelinesAccessor.ukpassthrough$register(RenderPipeline.builder(RenderPipelinesAccessor.ukpassthrough$globalsSnippet())
		.withLocation(Identifier.fromNamespaceAndPath(UKPassthrough.ID, "pipeline/held_item_mask"))
		.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(UKPassthrough.ID, "core/held_item_mask"))
		.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
		.build());

	private static Copies current;
	/** The previous size's copies, closed one resize later: this frame's GUI may still point at them. */
	private static Copies retired;
	private static boolean terrainDrawn;

	private record Copies(int width, int height, GpuTexture terrain, GpuTextureView terrainView, GpuTexture cutout, GpuTextureView cutoutView, GpuTexture scene,
		GpuTextureView sceneView, GpuTexture held, GpuTextureView heldView, GpuTexture keep, GpuTextureView keepView) {
		void close() {
			this.terrainView.close();
			this.terrain.close();
			this.cutoutView.close();
			this.cutout.close();
			this.keepView.close();
			this.keep.close();
			this.sceneView.close();
			this.scene.close();
			this.heldView.close();
			this.held.close();
		}
	}

	private StainMask() {
	}

	/** At start-up, so the pipelines are registered before the shaders load. */
	static void init() {
		UKPassthrough.LOG.debug("stain mask pipelines: {}, {}", HELD_ITEM.getLocation(), KEEP.getLocation());
	}

	/** Development (the dev demo's before and after): false draws the cut-out terrain (grass) with the solid, so blood goes over it, as before round 29c. */
	private static boolean groundOnly = true;

	/** The depth copy is of the solid terrain only, without the cut-out layer (grass, flowers: the blood stays behind them; LevelRendererMixin). */
	public static boolean groundOnly() {
		return groundOnly;
	}

	static void groundOnly(final boolean on) {
		groundOnly = on;
		UKPassthrough.LOG.info("stain mask: the depth copy {}", on ? "without grass, flowers and other cut-out blocks" : "with the cut-out blocks (before round 29c)");
	}

	/**
	 * Whether this frame draws the terrain on its own first: while V1 drives, unless blood stains are off in the settings and
	 * there's no gasoline either (round 25: then the extra terrain pass and the depth copies are saved).
	 */
	public static boolean active() {
		return Control.driving() && (ModSettings.get().bloodStains || FrameOverlay.oilShowing());
	}

	/** Start of the main pass, every frame. */
	public static void beginFrame() {
		terrainDrawn = false;
	}

	/** The solid terrain is drawn and nothing else on top yet: keep its depth. */
	public static void terrainDrawn(final RenderTarget main) {
		copy(main.getDepthTexture(), copies(main).terrain, main);
		terrainDrawn = true;
	}

	/** The cut-out terrain is drawn over the solid (grass, flowers): keep the depth with it too. */
	public static void cutoutDrawn(final RenderTarget main) {
		copy(main.getDepthTexture(), copies(main).cutout, main);
	}

	/**
	 * Before the stain and gasoline passes (FrameOverlay), with this frame's copies all taken: how much blood each pixel takes, into
	 * {@link #keepView} (stain_keep.fsh).
	 */
	static void compose() {
		Copies c = copies(main());
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			RenderPassDescriptor.builder(() -> "UKPassthrough stain keep").withColorAttachment(c.keepView).build())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setPipeline(RenderSystem.getCompiledPipeline(KEEP));
			pass.setUniform("Sampler0", c.terrainView, nearest);
			pass.setUniform("Sampler1", c.cutoutView, nearest);
			pass.setUniform("Sampler2", c.sceneView, nearest);
			pass.draw(3, 1, 0, 0);
		}
	}

	/** For the stain and gasoline passes (Sampler1): how much blood each pixel takes (red), as {@link #compose} left it. */
	static GpuTextureView keepView() {
		return copies(main()).keepView;
	}

	/** True once per frame in which the terrain was already drawn: the main pass then leaves it out. */
	public static boolean takeTerrainDrawn() {
		boolean drawn = terrainDrawn;
		terrainDrawn = false;
		return drawn;
	}

	/** After the main pass (solid terrain, mobs, block entities, translucent blocks), before anything clears depth. */
	public static void sceneDrawn(final RenderTarget main) {
		if (active()) {
			copy(main.getDepthTexture(), copies(main).scene, main);
		}
	}

	/**
	 * Minecraft has drawn the item V1 holds, against {@code depth} (cleared to far, 0, just before): where it was drawn goes
	 * into the copy with the mobs, so it differs from the terrain's there and the stain pass leaves the item alone.
	 */
	public static void heldItemDrawn(final GpuTextureView depth) {
		if (!active()) {
			return;
		}

		RenderTarget main = main();
		Copies c = copies(main);
		// the depth it was drawn against may be the one being drawn into elsewhere: sampled from a copy
		copy(depth.texture(), c.held, main);
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			RenderPassDescriptor.builder(() -> "UKPassthrough held item mask").withDepthAttachment(c.sceneView).build())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setPipeline(RenderSystem.getCompiledPipeline(HELD_ITEM));
			pass.setUniform("InSampler", c.heldView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	/** For the picture's shader (Sampler1): depth with only the solid terrain. */
	static GpuTextureView terrainView() {
		return copies(main()).terrainView;
	}

	/** For the picture's shader (Sampler2): depth with everything solid. */
	static GpuTextureView sceneView() {
		return copies(main()).sceneView;
	}

	private static RenderTarget main() {
		return Minecraft.getInstance().gameRenderer.mainRenderTarget();
	}

	private static void copy(final GpuTexture from, final GpuTexture into, final RenderTarget main) {
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(from, into, 0, 0, 0, 0, 0, Math.min(main.width, from.getWidth(0)),
			Math.min(main.height, from.getHeight(0)));
	}

	/** The copies at the main target's size, made (and cleared) when missing or the window changed size. */
	private static Copies copies(final RenderTarget main) {
		if (current != null && current.width == main.width && current.height == main.height) {
			return current;
		}

		if (retired != null) {
			retired.close();
		}

		retired = current;
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		GpuTexture terrain = device.createTexture("UKPassthrough terrain depth", USAGE, main.getDepthTexture().getFormat(), main.width, main.height, 1, 1);
		GpuTexture cutout = device.createTexture("UKPassthrough cut-out terrain depth", USAGE, main.getDepthTexture().getFormat(), main.width, main.height, 1, 1);
		GpuTexture scene = device.createTexture("UKPassthrough scene depth", USAGE, main.getDepthTexture().getFormat(), main.width, main.height, 1, 1);
		GpuTexture held = device.createTexture("UKPassthrough held item depth", USAGE, main.getDepthTexture().getFormat(), main.width, main.height, 1, 1);
		GpuTexture keep = device.createTexture("UKPassthrough stain keep", USAGE, GpuFormat.RGBA8_UNORM, main.width, main.height, 1, 1);
		// far, in 26.3's depth (near is 1)
		encoder.clearDepthTexture(terrain, 0.0);
		encoder.clearDepthTexture(cutout, 0.0);
		encoder.clearDepthTexture(scene, 0.0);
		encoder.clearDepthTexture(held, 0.0);
		current = new Copies(main.width, main.height, terrain, device.createTextureView(terrain), cutout, device.createTextureView(cutout), scene, device.createTextureView(scene),
			held, device.createTextureView(held), keep, device.createTextureView(keep));
		return current;
	}
}
