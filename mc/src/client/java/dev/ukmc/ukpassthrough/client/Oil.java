package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.ARGB;

/**
 * Gasoline on mobs: ULTRAKILL keeps it as fuel on a stand-in ({"t":"oil","id":mob,"f":0 to 5}), and here the mob's
 * model is tinted to an oily amber for it (LivingEntityRendererMixin), as ULTRAKILL darkens a gasolined enemy. Fuel
 * burns away while the mob burns, and the tint with it.
 */
public final class Oil {
	/** What a fully oiled mob's colours are multiplied by. */
	private static final float[] OILED = {0.58F, 0.44F, 0.22F};
	private static final Map<Integer, Float> FUEL = new ConcurrentHashMap<>();

	private Oil() {
	}

	/** From the link thread. */
	static void onOil(final JsonObject m) {
		int id = m.get("id").getAsInt();
		float fuel = m.get("f").getAsFloat();
		if (fuel > 0.0F) {
			FUEL.put(id, fuel);
		} else {
			FUEL.remove(id);
		}
	}

	/** How oiled a mob looks, 0 to 1: any gasoline shows, more darkens further (ULTRAKILL's fuel goes up to 5). */
	public static float amount(final int id) {
		Float fuel = FUEL.get(id);
		return fuel == null ? 0.0F : Math.min(1.0F, 0.45F + 0.55F * fuel / 5.0F);
	}

	/** The model's tint (ARGB), oiled by {@code amount}. */
	public static int tint(final int color, final float amount) {
		return ARGB.color(ARGB.alpha(color),
			Math.round(ARGB.red(color) * (1.0F + (OILED[0] - 1.0F) * amount)),
			Math.round(ARGB.green(color) * (1.0F + (OILED[1] - 1.0F) * amount)),
			Math.round(ARGB.blue(color) * (1.0F + (OILED[2] - 1.0F) * amount)));
	}

	/** A new world: the old ids mean nothing. */
	static void clear() {
		FUEL.clear();
	}

	/** Implemented by LivingEntityRenderState (LivingEntityRenderStateMixin): the oil found when the state was filled. */
	public interface Tinted {
		float ukpassthrough$oil();

		void ukpassthrough$setOil(float amount);
	}
}
