package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Minecraft's light where V1's camera is, for ULTRAKILL ({"t":"light","c":[r,g,b]}), so V1's arms and gun are lit
 * like Steve's hand would be: dark in a cave at night, warm by a torch, full in the sun. The colour is Minecraft's own
 * lightmap entry for the block and sky light there, worked out from the same inputs and the same formula as
 * assets/minecraft/shaders/core/lightmap.fsh (time of day, the Nether's ambient light, the brightness option, night
 * vision, the darkness effect). Client thread, every tick while V1 drives.
 */
final class V1Light {
	/** A change smaller than this (per channel) isn't worth a message. */
	private static final float STEP = 1.0F / 255.0F;
	private static final Vector3fc WHITE = new Vector3f(1.0F, 1.0F, 1.0F);
	private static final Vector3f sent = new Vector3f(-1.0F);
	private static int quietTicks;

	private V1Light() {
	}

	static void tick(final Minecraft minecraft) {
		V1State.Pose p = V1State.live();
		if (!Control.driving() || p == null || minecraft.level == null) {
			sent.set(-1.0F);
			return;
		}

		// the camera's block, or the one above it when V1 slides with its camera inside a block
		BlockPos at = BlockPos.containing(p.x(), p.y(), p.z());
		int block = Math.max(minecraft.level.getBrightness(LightLayer.BLOCK, at), minecraft.level.getBrightness(LightLayer.BLOCK, at.above()));
		int sky = Math.max(minecraft.level.getBrightness(LightLayer.SKY, at), minecraft.level.getBrightness(LightLayer.SKY, at.above()));
		Vector3f c = color(minecraft.gameRenderer.gameRenderState().lightmapRenderState, block, sky);
		quietTicks++;
		if (Math.abs(c.x - sent.x) < STEP && Math.abs(c.y - sent.y) < STEP && Math.abs(c.z - sent.z) < STEP && quietTicks < 20) {
			return;
		}

		sent.set(c);
		quietTicks = 0;
		JsonObject m = UkLink.type("light");
		JsonArray rgb = new JsonArray();
		rgb.add(Math.round(c.x * 1000.0F) / 1000.0F);
		rgb.add(Math.round(c.y * 1000.0F) / 1000.0F);
		rgb.add(Math.round(c.z * 1000.0F) / 1000.0F);
		m.add("c", rgb);
		m.addProperty("block", block);
		m.addProperty("sky", sky);
		UkLink.get().send(m);
	}

	/**
	 * From the link thread, {"t":"lightat","p":[id,x,y,z,...]}: the light colour at each point ULTRAKILL asks about (its enemies,
	 * props and gore, about where a mob's eyes would be: Minecraft lights a mob by its eye block), answered on the client thread
	 * with {"t":"lightat","c":[id,r,g,b,...]}, worked out as for V1. The block and sky light are the most of the point's block and
	 * the ones above and below it, so a point inside a block (a big enemy's head in a ceiling, a corpse's middle in the floor)
	 * takes the light beside it rather than the block's darkness.
	 */
	static void onLightAt(final JsonObject m) {
		JsonArray p = m.getAsJsonArray("p");
		Minecraft minecraft = Minecraft.getInstance();
		if (p == null) {
			return;
		}

		minecraft.execute(() -> {
			if (minecraft.level == null) {
				return;
			}

			LightmapRenderState s = minecraft.gameRenderer.gameRenderState().lightmapRenderState;
			JsonArray c = new JsonArray();
			for (int i = 0; i + 3 < p.size(); i += 4) {
				BlockPos at = BlockPos.containing(p.get(i + 1).getAsDouble(), p.get(i + 2).getAsDouble(), p.get(i + 3).getAsDouble());
				int block = 0;
				int sky = 0;
				for (BlockPos b : new BlockPos[] {at, at.above(), at.below()}) {
					block = Math.max(block, minecraft.level.getBrightness(LightLayer.BLOCK, b));
					sky = Math.max(sky, minecraft.level.getBrightness(LightLayer.SKY, b));
				}

				Vector3f col = color(s, block, sky);
				c.add(p.get(i));
				c.add(Math.round(col.x * 1000.0F) / 1000.0F);
				c.add(Math.round(col.y * 1000.0F) / 1000.0F);
				c.add(Math.round(col.z * 1000.0F) / 1000.0F);
			}

			JsonObject reply = UkLink.type("lightat");
			reply.add("c", c);
			UkLink.get().send(reply);
		});
	}

	/** lightmap.fsh, for one entry: block and sky light 0 to 15. */
	static Vector3f color(final LightmapRenderState s, final int blockLight, final int skyLight) {
		if (s.ambientColor == null || s.skyLightColor == null || s.blockLightTint == null) {
			// not worked out yet (the first frame)
			return new Vector3f(WHITE);
		}

		float blockLevel = blockLight / 15.0F;
		float skyLevel = skyLight / 15.0F;
		float blockBrightness = brightness(blockLevel) * s.blockFactor;
		float skyBrightness = brightness(skyLevel) * s.skyFactor;
		Vector3f color = new Vector3f(s.ambientColor);
		if (s.nightVisionColor != null) {
			color.max(new Vector3f(s.nightVisionColor).mul(s.nightVisionEffectIntensity));
		}

		color.add(new Vector3f(s.skyLightColor).mul(skyBrightness));
		float mix = 0.9F * (2.0F * blockLevel - 1.0F) * (2.0F * blockLevel - 1.0F);
		Vector3f blockColor = new Vector3f(s.blockLightTint).lerp(WHITE, mix);
		color.add(blockColor.mul(blockBrightness));
		color.lerp(new Vector3f(color).mul(0.7F, 0.6F, 0.6F), s.bossOverlayWorldDarkening);
		color.sub(s.darknessEffectScale, s.darknessEffectScale, s.darknessEffectScale);
		color.set(clamp(color.x), clamp(color.y), clamp(color.z));
		float max = Math.max(color.x, Math.max(color.y, color.z));
		if (max > 0.0F) {
			float inverted = 1.0F - max;
			float scaled = 1.0F - inverted * inverted * inverted * inverted;
			color.lerp(new Vector3f(color).mul(scaled / max), s.brightness);
		}

		return color;
	}

	private static float brightness(final float level) {
		return level / (4.0F - 3.0F * level);
	}

	private static float clamp(final float v) {
		return Math.max(0.0F, Math.min(1.0F, v));
	}
}
