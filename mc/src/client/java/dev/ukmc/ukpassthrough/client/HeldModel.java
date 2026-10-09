package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.ukmc.ukpassthrough.client.mixin.ItemStackRenderStateAccessor;
import dev.ukmc.ukpassthrough.client.mixin.LayerRenderStateAccessor;
import dev.ukmc.ukpassthrough.client.mixin.SpriteContentsAccessor;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The look of a block V1 holds, for ULTRAKILL to draw in its hand (round 14): the item model's own quads, as Minecraft draws
 * the item, and the textures they use. In the "held" message as
 * {"m":{"tex":[{"w":16,"h":16,"px":base64 RGBA, rows top first}, ...],"q":[[tex, face, tint, x,y,z,u,v (four times)], ...]}}:
 * positions in the model's space (a full block is 0 to 1 on each axis, Minecraft's x east, y up, z south), u and v within the
 * texture (0 to 1, v down), face the quad's direction (Direction's ordinal), tint the ARGB colour Minecraft multiplies it by
 * (grass and leaves' green; -1 for none). Only the first frame of an animated texture. A model drawn by code instead of quads
 * (chests, beds, banners, shulker boxes) comes as a cube with its particle texture. Client thread.
 */
final class HeldModel {
	private HeldModel() {
	}

	/** {@code stack}'s model, resolved as for an item in V1's hand. */
	static ItemStackRenderState resolve(final Minecraft minecraft, final ItemStack stack) {
		ItemStackRenderState state = new ItemStackRenderState();
		minecraft.getItemModelResolver().updateForLiving(state, stack, ItemDisplayContext.FIRST_PERSON_LEFT_HAND, minecraft.player);
		return state;
	}

	/** Drawn as a block in the inventory (lit as a block, in 3D), which is what V1 holds as ULTRAKILL holds a skull. */
	static boolean isBlock(final ItemStackRenderState state) {
		return !state.isEmpty() && state.usesBlockLight();
	}

	static JsonObject write(final ItemStackRenderState state) {
		List<TextureAtlasSprite> sprites = new ArrayList<>();
		Map<TextureAtlasSprite, Integer> index = new IdentityHashMap<>();
		JsonArray quads = new JsonArray();
		ItemStackRenderStateAccessor layers = (ItemStackRenderStateAccessor) state;
		for (int l = 0; l < layers.ukpassthrough$activeLayerCount(); l++) {
			ItemStackRenderState.LayerRenderState layer = layers.ukpassthrough$layers()[l];
			ItemQuads q = ((LayerRenderStateAccessor) layer).ukpassthrough$quads();
			if (q == null) {
				continue;
			}

			Matrix4f transform = ((LayerRenderStateAccessor) layer).ukpassthrough$localTransform();
			IntList tints = layer.tintLayers();
			for (BakedQuad quad : q.all()) {
				TextureAtlasSprite sprite = quad.materialInfo().sprite();
				int tex = index.computeIfAbsent(sprite, s -> {
					sprites.add(s);
					return sprites.size() - 1;
				});
				int tintIndex = quad.materialInfo().tintIndex();
				int tint = tintIndex >= 0 && tintIndex < tints.size() ? tints.getInt(tintIndex) : -1;
				JsonArray a = new JsonArray();
				a.add(tex);
				a.add(quad.direction().ordinal());
				a.add(tint);
				Vector3f p = new Vector3f();
				for (int v = 0; v < 4; v++) {
					transform.transformPosition(quad.position(v), p);
					long uv = quad.packedUV(v);
					addVertex(a, p.x, p.y, p.z, local(UVPair.unpackU(uv), sprite.getU0(), sprite.getU1()), local(UVPair.unpackV(uv), sprite.getV0(), sprite.getV1()));
				}

				quads.add(a);
			}
		}

		if (quads.isEmpty()) {
			Material.Baked particle = state.pickParticleMaterial(RandomSource.create(0L));
			if (particle != null) {
				sprites.add(particle.sprite());
				cube(quads);
			}
		}

		JsonArray textures = new JsonArray();
		for (TextureAtlasSprite sprite : sprites) {
			textures.add(texture(sprite.contents()));
		}

		JsonObject m = new JsonObject();
		m.add("tex", textures);
		m.add("q", quads);
		return m;
	}

	private static float local(final float atlas, final float from, final float to) {
		return to == from ? 0.0F : (atlas - from) / (to - from);
	}

	private static void addVertex(final JsonArray a, final float x, final float y, final float z, final float u, final float v) {
		for (float f : new float[] {x, y, z, u, v}) {
			a.add(Math.round(f * 10000.0F) / 10000.0F);
		}
	}

	/** A full block's six faces, all with the first texture, each seen from outside going round the way Minecraft's quads do. */
	private static void cube(final JsonArray quads) {
		float[][][] faces = {
			{{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},
			{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
			{{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}},
			{{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}},
			{{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}},
			{{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}},
		};
		float[][] uvs = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
		for (Direction d : Direction.values()) {
			JsonArray a = new JsonArray();
			a.add(0);
			a.add(d.ordinal());
			a.add(-1);
			for (int v = 0; v < 4; v++) {
				float[] p = faces[d.ordinal()][v];
				addVertex(a, p[0], p[1], p[2], uvs[v][0], uvs[v][1]);
			}

			quads.add(a);
		}
	}

	/** The texture's first frame as RGBA bytes, top row first. */
	private static JsonObject texture(final SpriteContents contents) {
		NativeImage image = ((SpriteContentsAccessor) contents).ukpassthrough$originalImage();
		int w = Math.min(contents.width(), image.getWidth());
		int h = Math.min(contents.height(), image.getHeight());
		byte[] px = new byte[w * h * 4];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = image.getPixel(x, y);
				int i = (y * w + x) * 4;
				px[i] = (byte) (argb >> 16);
				px[i + 1] = (byte) (argb >> 8);
				px[i + 2] = (byte) argb;
				px[i + 3] = (byte) (argb >>> 24);
			}
		}

		JsonObject t = new JsonObject();
		t.addProperty("n", contents.name().toString());
		t.addProperty("w", w);
		t.addProperty("h", h);
		t.addProperty("px", Base64.getEncoder().encodeToString(px));
		return t;
	}
}
