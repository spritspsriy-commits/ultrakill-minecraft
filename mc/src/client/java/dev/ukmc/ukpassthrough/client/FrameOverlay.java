package dev.ukmc.ukpassthrough.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.ukmc.ukpassthrough.UKPassthrough;
import dev.ukmc.ukpassthrough.client.mixin.GuiGraphicsExtractorAccessor;
import dev.ukmc.ukpassthrough.client.mixin.RenderPipelinesAccessor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/**
 * ULTRAKILL's picture over Minecraft's frame while V1 drives: V1's arms and weapons, shots, explosions and
 * ULTRAKILL's HUD, and blood stains on Minecraft's blocks (multiplied into the frame, so they're lit like the block). The plugin writes each frame to the shared memory "Local\UKPassthroughFrame" (layout in
 * uk/src/SharedFrame.cs): the picture, the stains as a plane of their own, and the Firestarter's gasoline, while there is any. Things behind Minecraft's blocks are already hidden there, because ULTRAKILL has
 * invisible copies of the blocks in its depth buffer, so here the picture only goes on top, with its alpha
 * premultiplied (glows add light, solid things cover). V1's picture for the inventory comes through the same shared memory
 * ({@link Portrait}). Render thread only.
 */
public final class FrameOverlay {
	private static final String NAME = "Local\\UKPassthroughFrame";
	static final int MAGIC = 0x46504B55;
	static final int VERSION = 4;
	private static final int SLOTS = 3;
	private static final long STAIN_OFFSET = 1920L * 1080L * 4L;
	private static final long OIL_OFFSET = 1920L * 1080L * 5L;
	static final long SLOT_BYTES = 1920L * 1080L * 6L;
	private static final long HEADER_BYTES = 256L;
	/** Where V1's portraits for the inventory start, after the frame slots ({@link Portrait}). */
	static final long PORTRAIT_OFFSET = HEADER_BYTES + SLOTS * SLOT_BYTES;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(UKPassthrough.ID, "ultrakill_picture");
	/**
	 * Minecraft's premultiplied GUI pipeline with our own fragment shader (ultrakill_picture.fsh): Minecraft's drops
	 * every pixel with alpha 0, which are ULTRAKILL's glows. Sampler1 and Sampler2 are the {@link StainMask} depth
	 * copies (only the mask debug view reads them here).
	 */
	static final RenderPipeline PIPELINE = RenderPipelinesAccessor.ukpassthrough$register(maskDebug(RenderPipeline.builder(RenderPipelinesAccessor.ukpassthrough$guiTexturedSnippet()))
		.withLocation(Identifier.fromNamespaceAndPath(UKPassthrough.ID, "pipeline/ultrakill_picture"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(UKPassthrough.ID, "core/ultrakill_picture"))
		.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
		.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
		.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
		.build());
	/**
	 * The stain plane, multiplied into Minecraft's frame (colour = frame x shader output, alpha kept), so the blood
	 * darkens whatever block it lies on with that block's own lighting: dark at night, red on stone, darker red on
	 * netherrack. Sampler1 says how much of it each pixel takes ({@link StainMask#compose}: none on mobs, some on grass).
	 */
	private static final RenderPipeline STAINS = RenderPipelinesAccessor.ukpassthrough$register(RenderPipeline.builder(RenderPipelinesAccessor.ukpassthrough$guiTexturedSnippet())
		.withLocation(Identifier.fromNamespaceAndPath(UKPassthrough.ID, "pipeline/ultrakill_stains"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(UKPassthrough.ID, "core/ultrakill_picture"))
		.withShaderDefine("STAIN_PASS")
		.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
		.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
		.withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.ZERO, BlendFactor.ZERO, BlendFactor.ONE)))
		.build());
	/**
	 * The gasoline plane, multiplied in the same way: ULTRAKILL's own colour for it (nearly black, with a blue and purple
	 * edge), over Minecraft's lit blocks. Half the picture's size, filtered smoothly.
	 */
	private static final RenderPipeline OIL = RenderPipelinesAccessor.ukpassthrough$register(RenderPipeline.builder(RenderPipelinesAccessor.ukpassthrough$guiTexturedSnippet())
		.withLocation(Identifier.fromNamespaceAndPath(UKPassthrough.ID, "pipeline/ultrakill_gasoline"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(UKPassthrough.ID, "core/ultrakill_picture"))
		.withShaderDefine("OIL_PASS")
		.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
		.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
		.withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.ZERO, BlendFactor.ZERO, BlendFactor.ONE)))
		.build());

	private static SharedMemory memory;
	private static boolean failed;
	private static boolean wrongVersion;
	private static DynamicTexture texture;
	/** The stain plane: one byte a pixel, the picture's size. */
	private static GpuTexture stains;
	private static GpuTextureView stainsView;
	/** The stain plane on its way to {@link #stains}, rows turned top-down. */
	private static ByteBuffer stainPixels;
	/** The gasoline plane: RGBA, half the picture's size (rounded up). */
	private static GpuTexture oil;
	private static GpuTextureView oilView;
	private static ByteBuffer oilPixels;
	/** The newest frame had gasoline. */
	private static boolean showingOil;
	private static int width;
	private static int height;
	private static long lastPublished = -1L;
	/** The texture holds a frame worth showing. */
	private static boolean showing;
	private static long copies;
	private static long copyNanos;

	private FrameOverlay() {
	}

	/** Development: with UKPASSTHROUGH_MASK_DEBUG=1 the picture shows the stain mask instead (see ultrakill_picture.fsh). */
	private static RenderPipeline.Builder maskDebug(final RenderPipeline.Builder builder) {
		return "1".equals(System.getenv("UKPASSTHROUGH_MASK_DEBUG")) ? builder.withShaderDefine("MASK_DEBUG") : builder;
	}

	/** At start-up, so the pipeline is registered before the shaders load (and gets compiled with Minecraft's). */
	static void init() {
		UKPassthrough.LOG.debug("ULTRAKILL picture pipelines: {}, {}, {}", PIPELINE.getLocation(), STAINS.getLocation(), OIL.getLocation());
		StainMask.init();
	}

	/** ULTRAKILL's gasoline is in the newest picture (its plane is drawn). */
	static boolean oilShowing() {
		return showing && showingOil;
	}

	/** A HUD element, drawn just above Minecraft's own screen overlays (vignette, pumpkin, portal). */
	static void extract(final GuiGraphicsExtractor graphics, final DeltaTracker delta) {
		if (!Control.driving()) {
			return;
		}

		update();
		if (showing) {
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			GpuTextureView terrain = StainMask.terrainView();
			GpuTextureView scene = StainMask.sceneView();
			boolean blood = ModSettings.get().bloodStains;
			if (showingOil || blood) {
				// how much of them each pixel takes (none on mobs, some on grass near the ground): the world is drawn by now
				StainMask.compose();
			}

			GpuTextureView keep = StainMask.keepView();
			// the stains first, then the picture over them (V1's gun in front of a stain covers it). No scissor: the whole
			// screen, as the HUD has. A full-screen element goes on a layer above the one before it, so they draw in this order.
			// Gasoline and blood both multiply, so between those two the order doesn't matter.
			if (showingOil) {
				blit(graphics, OIL, new TextureSetup(oilView, keep, scene, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR), nearest, nearest));
			}

			if (blood) {
				blit(graphics, STAINS, new TextureSetup(stainsView, keep, scene, nearest, nearest, nearest));
			}

			blit(graphics, PIPELINE, new TextureSetup(texture.getTextureView(), terrain, scene, texture.getSampler(), nearest, nearest));
		}
	}

	private static void blit(final GuiGraphicsExtractor graphics, final RenderPipeline pipeline, final TextureSetup textures) {
		((GuiGraphicsExtractorAccessor) graphics).ukpassthrough$guiRenderState().addGuiElement(new BlitRenderState(pipeline, textures, new Matrix3x2f(graphics.pose()), 0, 0,
			graphics.guiWidth(), graphics.guiHeight(), 0.0F, 1.0F, 0.0F, 1.0F, -1, null));
	}

	/** Copies the newest frame into the textures, if there is a new one. */
	private static void update() {
		MemorySegment m = memory();
		if (m == null || m.get(ValueLayout.JAVA_INT_UNALIGNED, 0L) != MAGIC) {
			showing = false;
			return;
		}

		if (m.get(ValueLayout.JAVA_INT_UNALIGNED, 4L) != VERSION || m.get(ValueLayout.JAVA_INT_UNALIGNED, 12L) != SLOT_BYTES) {
			if (!wrongVersion) {
				wrongVersion = true;
				UKPassthrough.LOG.error("ULTRAKILL's picture comes in layout version {}, this mod reads {}: install both halves from the same build", m.get(ValueLayout.JAVA_INT_UNALIGNED, 4L), VERSION);
				UkLink.chat("§cULTRAKILL's plugin and this mod are from different builds: install both again");
			}

			showing = false;
			return;
		}

		long published = m.get(ValueLayout.JAVA_LONG_UNALIGNED, 24L);
		if (published == lastPublished) {
			return;
		}

		VarHandle.acquireFence();
		int slot = m.get(ValueLayout.JAVA_INT_UNALIGNED, 16L);
		if (slot < 0 || slot >= SLOTS) {
			// ULTRAKILL stopped sending (V1 mode off there, or it's loading)
			lastPublished = published;
			showing = false;
			return;
		}

		long header = 64L + 64L * slot;
		long seq = m.get(ValueLayout.JAVA_LONG_UNALIGNED, header);
		if ((seq & 1L) != 0L) {
			return;
		}

		VarHandle.acquireFence();
		int w = m.get(ValueLayout.JAVA_INT_UNALIGNED, header + 8L);
		int h = m.get(ValueLayout.JAVA_INT_UNALIGNED, header + 12L);
		int flags = m.get(ValueLayout.JAVA_INT_UNALIGNED, header + 20L);
		boolean bottomUp = (flags & 1) != 0;
		if (w <= 0 || h <= 0 || (long)w * h * 4L > STAIN_OFFSET || (flags & 2) == 0) {
			return;
		}

		if (texture == null || w != width || h != height) {
			resize(w, h);
		}

		long start = System.nanoTime();
		long row = w * 4L;
		long base = HEADER_BYTES + slot * SLOT_BYTES;
		boolean hasOil = (flags & 4) != 0;
		int ow = oilSize(w);
		int oh = oilSize(h);
		copyRows(m.asSlice(base, row * h), MemorySegment.ofAddress(texture.getPixels().getPointer()).reinterpret(row * h), row, h, bottomUp);
		copyRows(m.asSlice(base + STAIN_OFFSET, (long)w * h), MemorySegment.ofBuffer(stainPixels), w, h, bottomUp);
		if (hasOil) {
			copyRows(m.asSlice(base + OIL_OFFSET, ow * 4L * oh), MemorySegment.ofBuffer(oilPixels), ow * 4L, oh, bottomUp);
		}

		VarHandle.acquireFence();
		if (m.get(ValueLayout.JAVA_LONG_UNALIGNED, header) != seq) {
			// overwritten while copying: the next frame comes in a moment
			return;
		}

		texture.upload();
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(stains, stainPixels, 0, 0, 0, 0, w, h);
		if (hasOil) {
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(oil, oilPixels, 0, 0, 0, 0, ow, oh);
		}

		showingOil = hasOil;
		lastPublished = published;
		showing = true;
		copies++;
		copyNanos += System.nanoTime() - start;
	}

	private static void copyRows(final MemorySegment from, final MemorySegment to, final long row, final int rows, final boolean flip) {
		if (flip) {
			for (int y = 0; y < rows; y++) {
				MemorySegment.copy(from, (rows - 1 - y) * row, to, y * row, row);
			}
		} else {
			MemorySegment.copy(from, 0L, to, 0L, row * rows);
		}
	}

	/** The gasoline plane's width or height for a picture's (SharedFrame.OilSize). */
	private static int oilSize(final int pictureSize) {
		return (pictureSize + 1) / 2;
	}

	/** The picture's, the stains' and the gasoline's textures at a new size. */
	private static void resize(final int w, final int h) {
		texture = new DynamicTexture(() -> "ULTRAKILL picture", w, h, false);
		// replaces (and closes) the previous size's texture
		Minecraft.getInstance().getTextureManager().register(ID, texture);
		if (stains != null) {
			stainsView.close();
			stains.close();
		}

		GpuDevice device = RenderSystem.getDevice();
		stains = device.createTexture(() -> "ULTRAKILL stains", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.R8_UNORM, w, h, 1, 1);
		stainsView = device.createTextureView(stains);
		stainPixels = ByteBuffer.allocateDirect(w * h);
		if (oil != null) {
			oilView.close();
			oil.close();
		}

		oil = device.createTexture(() -> "ULTRAKILL gasoline", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, oilSize(w), oilSize(h), 1, 1);
		oilView = device.createTextureView(oil);
		oilPixels = ByteBuffer.allocateDirect(oilSize(w) * oilSize(h) * 4);
		showingOil = false;
		width = w;
		height = h;
	}

	/** How many frames were taken over and what that cost per frame on the render thread, for the dev demo's log. */
	static String stats() {
		return String.format(java.util.Locale.ROOT, "%d frames %dx%d, %.2f ms each to copy and upload", copies, width, height, copies == 0 ? 0.0 : copyNanos / 1.0e6 / copies);
	}

	/** The shared memory, opened the first time it's asked for, or null if it can't be. */
	static MemorySegment memory() {
		if (memory == null && !failed) {
			try {
				memory = SharedMemory.open(NAME, PORTRAIT_OFFSET + Portrait.REGION_BYTES);
			} catch (Throwable e) {
				failed = true;
				UKPassthrough.LOG.error("no shared memory for ULTRAKILL's picture", e);
				UkLink.chat("§cULTRAKILL's picture can't reach Minecraft (shared memory failed, see the log)");
			}
		}

		return memory == null ? null : memory.segment;
	}
}
