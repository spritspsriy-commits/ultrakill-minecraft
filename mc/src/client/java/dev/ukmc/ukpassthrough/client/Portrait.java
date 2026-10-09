package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.ukmc.ukpassthrough.UKPassthrough;
import dev.ukmc.ukpassthrough.client.mixin.GuiGraphicsExtractorAccessor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3x2f;

/**
 * V1 in the inventory instead of Steve (round 15; uk/src/Portrait.cs on the other side). Minecraft draws its player there
 * (the survival inventory, and the creative inventory's inventory tab) with InventoryScreen.extractEntityInInventoryFollowsMouse;
 * while V1 drives, that draws V1 instead (InventoryScreenMixin). ULTRAKILL draws V1's body for the box, asked for with
 * {"t":"portrait","on":true,"n":inventory,"w":px,"h":px,"s":px per block,"y":lift,"mx":rad,"my":rad} (the box's size on the
 * screen, Minecraft's scale for its player there, and the mouse's angles from the box's middle, worked out as Minecraft does),
 * and the picture comes back through the shared memory, after the frame slots (layout in uk/src/SharedFrame.cs). Until the
 * first one for this inventory arrives the box stays empty. Minecraft draws the box every frame while the inventory is open;
 * once it hasn't for a moment, ULTRAKILL hears that it closed ({"t":"portrait","on":false}). Render thread (the client's).
 */
public final class Portrait {
	private static final int MAX = 512;
	private static final int SLOTS = 3;
	private static final long BYTES = MAX * MAX * 4L;
	/** The portraits' part of the shared memory: a header, then the slots. */
	static final long REGION_BYTES = 256L + SLOTS * BYTES;
	private static final long OFFSET = FrameOverlay.PORTRAIT_OFFSET;
	/** The inventory draws its player every frame: this long without, it has closed. */
	private static final long CLOSED_NANOS = 250_000_000L;
	/** The mouse moving sends a new ask at most this often; one goes anyway this often (a new ULTRAKILL hears of it). */
	private static final long ASK_NANOS = 10_000_000L;
	private static final long REMIND_NANOS = 1_000_000_000L;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(UKPassthrough.ID, "v1_portrait");

	private static DynamicTexture texture;
	private static int width;
	private static int height;
	/** The texture holds V1 for {@link #request}. */
	private static boolean showing;
	private static long lastPublished = -1L;
	/** Counts the inventories opened while V1 drives; ULTRAKILL's pictures carry the one they were drawn for. */
	private static int request;
	/** ULTRAKILL was told an inventory is open. */
	private static boolean asked;
	private static int askedGeneration = -1;
	private static long lastDraw;
	private static long lastAsk;
	private static int askedW;
	private static int askedH;
	private static int askedScale;
	private static float askedMx;
	private static float askedMy;
	private static int received;

	private Portrait() {
	}

	/**
	 * From InventoryScreenMixin, with that method's arguments: true when V1 is drawn here (or will be, once its picture
	 * arrives) and Steve mustn't be.
	 */
	public static boolean draw(final GuiGraphicsExtractor graphics, final int x0, final int y0, final int x1, final int y1, final int size, final float lift, final float mouseX,
		final float mouseY, final LivingEntity entity) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!Control.driving() || entity != minecraft.player || x1 <= x0 || y1 <= y0) {
			return false;
		}

		long now = System.nanoTime();
		lastDraw = now;
		int scale = minecraft.getWindow().getGuiScale();
		// as Minecraft turns its player towards the mouse
		float mx = (float)Math.atan(((x0 + x1) / 2.0F - mouseX) / 40.0F);
		float my = (float)Math.atan(((y0 + y1) / 2.0F - mouseY) / 40.0F);
		ask(now, (x1 - x0) * scale, (y1 - y0) * scale, size * scale, lift, mx, my);
		update();
		if (showing) {
			GpuTextureView view = texture.getTextureView();
			GpuSampler sampler = texture.getSampler();
			// the picture's own pipeline: premultiplied, as ULTRAKILL draws (its other two samplers are only read by its debug view)
			((GuiGraphicsExtractorAccessor) graphics).ukpassthrough$guiRenderState().addGuiElement(new BlitRenderState(FrameOverlay.PIPELINE,
				new TextureSetup(view, view, view, sampler, sampler, sampler), new Matrix3x2f(graphics.pose()), x0, y0, x1, y1, 0.0F, 1.0F, 0.0F, 1.0F, -1, null));
		}

		return true;
	}

	private static void ask(final long now, final int w, final int h, final int pixelsPerBlock, final float lift, final float mx, final float my) {
		UkLink link = UkLink.get();
		boolean fresh = !asked;
		if (fresh) {
			request++;
			showing = false;
			received = 0;
			UKPassthrough.LOG.info("V1's portrait: inventory {} open, {}x{} pixels, {} per block", request, w, h, pixelsPerBlock);
		}

		boolean changed = w != askedW || h != askedH || pixelsPerBlock != askedScale || Math.abs(mx - askedMx) > 1.0E-4F || Math.abs(my - askedMy) > 1.0E-4F;
		if (!fresh && link.generation() == askedGeneration && !(changed && now - lastAsk >= ASK_NANOS) && now - lastAsk < REMIND_NANOS) {
			return;
		}

		asked = true;
		askedGeneration = link.generation();
		lastAsk = now;
		askedW = w;
		askedH = h;
		askedScale = pixelsPerBlock;
		askedMx = mx;
		askedMy = my;
		JsonObject m = UkLink.type("portrait");
		m.addProperty("on", true);
		m.addProperty("n", request);
		m.addProperty("w", w);
		m.addProperty("h", h);
		m.addProperty("s", pixelsPerBlock);
		m.addProperty("y", lift);
		m.addProperty("mx", mx);
		m.addProperty("my", my);
		link.send(m);
	}

	/** Every client tick: an inventory that stopped drawing its player has closed. */
	static void tick() {
		if (asked && (System.nanoTime() - lastDraw > CLOSED_NANOS || !Control.driving())) {
			asked = false;
			showing = false;
			JsonObject m = UkLink.type("portrait");
			m.addProperty("on", false);
			UkLink.get().send(m);
			UKPassthrough.LOG.info("V1's portrait: inventory {} closed ({} pictures of V1 shown)", request, received);
		}
	}

	/** The newest picture of V1 for this inventory into the texture, if there is one. */
	private static void update() {
		MemorySegment m = FrameOverlay.memory();
		if (m == null || m.get(ValueLayout.JAVA_INT_UNALIGNED, 0L) != FrameOverlay.MAGIC || m.get(ValueLayout.JAVA_INT_UNALIGNED, 4L) != FrameOverlay.VERSION
			|| m.get(ValueLayout.JAVA_INT_UNALIGNED, 12L) != FrameOverlay.SLOT_BYTES) {
			// FrameOverlay says so in the chat
			return;
		}

		long published = m.get(ValueLayout.JAVA_LONG_UNALIGNED, OFFSET);
		if (published == lastPublished) {
			return;
		}

		VarHandle.acquireFence();
		int slot = m.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET + 8L);
		if (slot < 0 || slot >= SLOTS) {
			lastPublished = published;
			return;
		}

		long header = OFFSET + 64L + 32L * slot;
		long seq = m.get(ValueLayout.JAVA_LONG_UNALIGNED, header);
		if ((seq & 1L) != 0L) {
			return;
		}

		VarHandle.acquireFence();
		int w = m.get(ValueLayout.JAVA_INT_UNALIGNED, header + 8L);
		int h = m.get(ValueLayout.JAVA_INT_UNALIGNED, header + 12L);
		int n = m.get(ValueLayout.JAVA_INT_UNALIGNED, header + 16L);
		boolean bottomUp = (m.get(ValueLayout.JAVA_INT_UNALIGNED, header + 20L) & 1) != 0;
		if (n != request || w <= 0 || h <= 0 || w > MAX || h > MAX) {
			// one drawn for an inventory open before this one
			lastPublished = published;
			return;
		}

		if (texture == null || w != width || h != height) {
			texture = new DynamicTexture(() -> "V1 in the inventory", w, h, false);
			// replaces (and closes) the previous size's texture
			Minecraft.getInstance().getTextureManager().register(ID, texture);
			width = w;
			height = h;
		}

		long row = w * 4L;
		MemorySegment from = m.asSlice(OFFSET + 256L + slot * BYTES, row * h);
		MemorySegment to = MemorySegment.ofAddress(texture.getPixels().getPointer()).reinterpret(row * h);
		for (int y = 0; y < h; y++) {
			MemorySegment.copy(from, (bottomUp ? h - 1 - y : y) * row, to, y * row, row);
		}

		VarHandle.acquireFence();
		if (m.get(ValueLayout.JAVA_LONG_UNALIGNED, header) != seq) {
			// overwritten while copying: the next one comes in a moment
			return;
		}

		texture.upload();
		lastPublished = published;
		showing = true;
		received++;
	}
}
