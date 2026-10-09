package dev.ukmc.ukpassthrough.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.ukmc.ukpassthrough.UKPassthrough;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Progression Mode (round 28, the user's request: "when this is on, all guns will be taken away and your ultrakill P will be reset to 0
 * (only in the mod). and then, from this point on, when you pickup minecraft exp it will turn into ultrakill p"). Asked, the user chose:
 * progress kept per world, experience becoming P only (not Minecraft experience), a weapon's alternate free once its three variants
 * are owned, and the arms kept. Each world's progress (its P and the weapons bought) is kept in {@code ukpassthrough_progression.json}
 * in that world's folder; the first time the mode is on in a world it starts there from nothing. ULTRAKILL gets it as
 * {"t":"progress","on":bool,"p":P,"gear":[...],"spent":n} (uk/src/Progression.cs): its guns are only the ones bought, its terminal
 * shows this P and sells for it, and each sale comes back as {"t":"spend","cost":P,"gear":name}, counted ("spent") so ULTRAKILL knows
 * which of its own it can forget. Experience orbs' points (after Mending) become P while the mode is on: {@link #P_PER_XP} each, or as
 * the balance settings say (round 30).
 * Client thread, unless said otherwise.
 */
final class Progression {
	/**
	 * ULTRAKILL's P for a point of Minecraft experience: a zombie's 5 points give 1,000 P. The first gun (the Piercer, 2,500 P here) takes
	 * three kills, ULTRAKILL's own variants (7,500 to 100,000 P) from a few to a hundred, the whole arsenal (about 600,000 P) some hours
	 * of fighting and mining (an Ender Dragon's 12,000 points would buy it all).
	 */
	static final int P_PER_XP = 200;
	private static final String FILE = "ukpassthrough_progression.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Saved this long after the last change (experience comes in many small orbs). */
	private static final long SAVE_AFTER_NANOS = 1_000_000_000L;

	/** One world's progress: its P and the weapons bought (ULTRAKILL's gear names, "rev0" to "rock2"). */
	static final class State {
		long p;
		List<String> gear = new ArrayList<>();
	}

	/** The server thread (experience) and the link thread (sales) change the progress too. */
	private static final Object LOCK = new Object();
	private static Path file;
	/** The open world's progress, or null while Progression Mode hasn't run in it. */
	private static State state;
	private static boolean dirty;
	private static long changedAt;
	private static boolean sendPending;
	private static int sentGeneration = -1;
	private static boolean sentOn;
	/** ULTRAKILL's sales applied on this connection. */
	private static int spent;
	/** P from experience not shown yet. */
	private static long gained;

	private Progression() {
	}

	/** At start-up: orbs' experience comes here first; the world's progress is saved as the world closes. */
	static void register() {
		UKPassthrough.xpPickup = Progression::take;
		// Save and Quit, or the game closed with a world open: saved now, not a second later (the client may not tick again before it ends;
		// round 29b, the user's "make sure progression mode data saves in the world")
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (save()) {
				UKPassthrough.LOG.info("progression: this world's progress saved as it closes");
			}
		});
	}

	/** Server thread: an orb's points (after Mending), taken as P while the mode is on in this world. */
	private static boolean take(final int points) {
		if (!ModSettings.get().progression) {
			return false;
		}

		synchronized (LOCK) {
			if (state == null) {
				return false;
			}

			long p = (long) points * Math.max(0, ModSettings.get().pPerXp);
			state.p += p;
			gained += p;
			changed();
		}

		return true;
	}

	/** From the link thread: {"t":"spend","cost":P,"gear":name}, a weapon bought at ULTRAKILL's terminal. */
	static void onSpend(final JsonObject m) {
		long cost = m.has("cost") ? m.get("cost").getAsLong() : 0L;
		String gear = m.has("gear") && !m.get("gear").isJsonNull() ? m.get("gear").getAsString() : null;
		synchronized (LOCK) {
			spent++;
			if (state != null) {
				state.p = Math.max(0L, state.p - Math.max(0L, cost));
				if (gear != null && !state.gear.contains(gear)) {
					state.gear.add(gear);
				}

				changed();
			}
		}

		UKPassthrough.LOG.info("progression: {} bought at ULTRAKILL's terminal for {} P", gear, cost);
	}

	/** Whether the open world's progress is in use (the mode on, and the world's progress there). */
	static boolean active() {
		synchronized (LOCK) {
			return ModSettings.get().progression && state != null;
		}
	}

	/** The open world's P, for the menu and /uk (0 without progress). */
	static long p() {
		synchronized (LOCK) {
			return state == null ? 0L : state.p;
		}
	}

	/** Every client tick: the open world's progress loaded or started, sent to ULTRAKILL when it changes, saved once it settles. */
	static void tick(final Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		Path now = server != null && minecraft.level != null ? server.getWorldPath(LevelResource.ROOT).resolve(FILE) : null;
		if (!Objects.equals(now, file)) {
			save();
			load(now);
		}

		boolean on = ModSettings.get().progression;
		synchronized (LOCK) {
			if (on && file != null && state == null) {
				// the first time in this world: no guns, no P
				state = new State();
				changed();
				UKPassthrough.LOG.info("progression: started in this world (no guns, 0 P)");
			}
		}

		UkLink link = UkLink.get();
		boolean connected = link != null && link.connected();
		if (connected && link.generation() != sentGeneration) {
			sentGeneration = link.generation();
			synchronized (LOCK) {
				spent = 0;
			}

			sendPending = true;
		}

		boolean active = active();
		if (connected && (sendPending || active != sentOn)) {
			sendPending = false;
			sentOn = active;
			link.send(message(active));
		}

		long shown;
		synchronized (LOCK) {
			shown = gained;
			gained = 0L;
		}

		if (shown > 0L && minecraft.player != null) {
			minecraft.player.sendOverlayMessage(Component.literal(String.format(Locale.ROOT, "+%,d ", shown)).append(Component.literal("P").withStyle(ChatFormatting.RED))
				.append(Component.literal(String.format(Locale.ROOT, "  (%,d P)", p())).withStyle(ChatFormatting.GRAY)));
		}

		if (dirty && System.nanoTime() - changedAt > SAVE_AFTER_NANOS) {
			save();
		}
	}

	private static JsonObject message(final boolean active) {
		JsonObject m = UkLink.type("progress");
		m.addProperty("on", active);
		synchronized (LOCK) {
			if (active) {
				m.addProperty("p", state.p);
				JsonArray gear = new JsonArray();
				state.gear.forEach(gear::add);
				m.add("gear", gear);
			}

			m.addProperty("spent", spent);
		}

		return m;
	}

	/** Development (the dev demo): this world's progress forgotten, its file deleted, so the mode starts here anew. */
	static void forget() {
		Path path;
		synchronized (LOCK) {
			path = file;
			state = null;
			dirty = false;
			sendPending = true;
		}

		try {
			if (path != null) {
				Files.deleteIfExists(path);
			}
		} catch (IOException e) {
			UKPassthrough.LOG.warn("couldn't delete {}: {}", path, e.toString());
		}
	}

	/** Under the lock: to be saved and sent. */
	private static void changed() {
		dirty = true;
		changedAt = System.nanoTime();
		sendPending = true;
	}

	private static void load(final Path path) {
		State loaded = null;
		if (path != null && Files.isRegularFile(path)) {
			try (Reader r = Files.newBufferedReader(path)) {
				loaded = GSON.fromJson(r, State.class);
				if (loaded != null && loaded.gear == null) {
					loaded.gear = new ArrayList<>();
				}
			} catch (IOException | JsonParseException e) {
				UKPassthrough.LOG.warn("couldn't read {} ({}): this world's progression starts again", path, e.toString());
			}
		}

		synchronized (LOCK) {
			file = path;
			state = loaded;
			dirty = false;
			gained = 0L;
			sendPending = true;
		}

		if (loaded != null) {
			UKPassthrough.LOG.info("progression: this world's progress loaded ({} P, bought {})", loaded.p, loaded.gear);
		}
	}

	/** The progress to its file if it changed (true when written). Client thread, or the server thread as the world closes. */
	private static boolean save() {
		Path path;
		String json;
		synchronized (LOCK) {
			if (!dirty || file == null || state == null) {
				return false;
			}

			dirty = false;
			path = file;
			json = GSON.toJson(state);
		}

		try {
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp)) {
				w.write(json);
			}

			Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException e) {
			UKPassthrough.LOG.warn("couldn't save {}: {}", path, e.toString());
			return false;
		}
	}
}
