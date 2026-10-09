package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.ukmc.ukpassthrough.UKPassthrough;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;

/**
 * Potato mode's side in Minecraft (round 25, the user's request: "a performance setting changing graphics options for both
 * minecraft and ultrakill ... whenever potato mode is turned off it will revert back to whatever settings the player had
 * previously"; they asked for two, potato mode and super potato mode). Minecraft's own options are changed (only ever lowered:
 * an option already below what potato mode wants stays), and each one's value before and after is kept with the settings
 * ({@link ModSettings.Values#potatoBefore}, saved at once), so the change outlives a restart or a crash. Turned off, each goes
 * back to what it was, unless the player has changed it since (then theirs stays). The graphics preset becomes "Custom" when an
 * option changes, as in the video settings, and goes back too. ULTRAKILL's side is uk/src/Settings.cs. Client thread.
 * <ul>
 * <li>Potato mode: what's barely seen. Render distance at most 10 and simulation distance 8, entities drawn at most as far as
 * normal, fast clouds over 64 blocks, fewer particles, no entity shadows, no improved transparency (Fabulous), biome blend 1,
 * rain and snow over 5 blocks.</li>
 * <li>Super potato mode: render distance at most 6 and simulation distance 5, entities drawn three quarters as far, no clouds,
 * minimal particles, no smooth lighting, fast leaves, no biome blend, rain and snow over 3 blocks, besides the above.</li>
 * </ul>
 */
final class Potato {
	static final int OFF = 0;
	static final int LIGHT = 1;
	static final int SUPER = 2;

	private record Option<T>(String name, Function<Options, OptionInstance<T>> option, UnaryOperator<T> light, UnaryOperator<T> full) {
	}

	private static final List<Option<?>> OPTIONS = List.of(
		new Option<>("renderDistance", Options::renderDistance, v -> Math.min(v, 10), v -> Math.min(v, 6)),
		new Option<>("simulationDistance", Options::simulationDistance, v -> Math.min(v, 8), v -> Math.min(v, 5)),
		new Option<>("entityDistanceScaling", Options::entityDistanceScaling, v -> Math.min(v, 1.0), v -> Math.min(v, 0.75)),
		new Option<>("cloudStatus", Options::cloudStatus, v -> v == CloudStatus.FANCY ? CloudStatus.FAST : v, v -> CloudStatus.OFF),
		new Option<>("cloudRange", Options::cloudRange, v -> Math.min(v, 64), v -> Math.min(v, 64)),
		new Option<>("particles", Options::particles, v -> v == ParticleStatus.ALL ? ParticleStatus.DECREASED : v, v -> ParticleStatus.MINIMAL),
		new Option<>("entityShadows", Options::entityShadows, v -> false, v -> false),
		new Option<>("improvedTransparency", Options::improvedTransparency, v -> false, v -> false),
		new Option<>("biomeBlendRadius", Options::biomeBlendRadius, v -> Math.min(v, 1), v -> 0),
		new Option<>("weatherRadius", Options::weatherRadius, v -> Math.min(v, 5), v -> Math.min(v, 3)),
		new Option<>("ambientOcclusion", Options::ambientOcclusion, v -> v, v -> false),
		new Option<>("cutoutLeaves", Options::cutoutLeaves, v -> v, v -> false)
	);

	private static final String PRESET = "graphicsPreset";

	private Potato() {
	}

	static String name(final int level) {
		return level == SUPER ? "super" : level == LIGHT ? "on" : "off";
	}

	/** The options potato mode touches, as they are now (for the log and the dev demo). */
	static String describe(final Options options) {
		StringBuilder sb = new StringBuilder();
		for (Option<?> o : OPTIONS) {
			sb.append(o.name()).append('=').append(encode(o.option().apply(options))).append(' ');
		}

		return sb.append(PRESET).append('=').append(encode(options.graphicsPreset())).toString();
	}

	/** From potato mode {@code values.potato} to {@code level}: Minecraft's options back as they were, then lowered again for the new level. */
	static void change(final ModSettings.Values values, final int level) {
		Options options = Minecraft.getInstance().options;
		int restored = restore(options, values);
		int lowered = 0;
		if (level != OFF) {
			JsonElement preset = encode(options.graphicsPreset());
			for (Option<?> o : OPTIONS) {
				lowered += lower(options, o, level, values);
			}

			if (lowered > 0) {
				// changing an option made the preset "Custom" (Options.setGraphicsPresetToCustom): it goes back with them
				values.potatoBefore.put(PRESET, preset);
				values.potatoSet.put(PRESET, encode(options.graphicsPreset()));
			}
		}

		options.save();
		UKPassthrough.LOG.info("potato mode {}: {} of Minecraft's options put back, {} lowered", name(level), restored, lowered);
	}

	private static <T> int lower(final Options options, final Option<T> o, final int level, final ModSettings.Values values) {
		OptionInstance<T> option = o.option().apply(options);
		T now = option.get();
		T wanted = (level == SUPER ? o.full() : o.light()).apply(now);
		Optional<T> valid = option.values().validateValue(wanted);
		if (valid.isEmpty() || Objects.equals(valid.get(), now)) {
			return 0;
		}

		JsonElement before = encode(option);
		option.set(valid.get());
		values.potatoBefore.put(o.name(), before);
		values.potatoSet.put(o.name(), encode(option));
		return 1;
	}

	/** Each option potato mode changed back to its old value, if it's still what potato mode set (the player may have changed it). */
	private static int restore(final Options options, final ModSettings.Values values) {
		int n = 0, changed = 0;
		for (Option<?> o : OPTIONS) {
			changed += values.potatoBefore.containsKey(o.name()) ? 1 : 0;
			n += restore(o.option().apply(options), o.name(), values) ? 1 : 0;
		}

		// the preset last, and only if every option came back: setting a real preset applies it (its options are what they were
		// with it anyway), which would undo one the player changed meanwhile (then "Custom" is right)
		if (n == changed) {
			restore(options.graphicsPreset(), PRESET, values);
		}
		values.potatoBefore.clear();
		values.potatoSet.clear();
		return n;
	}

	private static <T> boolean restore(final OptionInstance<T> option, final String name, final ModSettings.Values values) {
		Map<String, JsonElement> before = values.potatoBefore, set = values.potatoSet;
		if (!before.containsKey(name) || !Objects.equals(encode(option), set.get(name))) {
			return false;
		}

		Optional<T> old = option.codec().parse(JsonOps.INSTANCE, before.get(name)).result();
		if (old.isEmpty()) {
			return false;
		}

		if (option.get() instanceof GraphicsPreset && old.get() == GraphicsPreset.CUSTOM) {
			// already Custom, and setting it again would change nothing
			return false;
		}

		option.set(old.get());
		return true;
	}

	private static <T> JsonElement encode(final OptionInstance<T> option) {
		return option.codec().encodeStart(JsonOps.INSTANCE, option.get()).result().orElse(null);
	}
}
