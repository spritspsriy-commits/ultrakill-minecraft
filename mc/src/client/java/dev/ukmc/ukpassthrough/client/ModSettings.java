package dev.ukmc.ukpassthrough.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.ukmc.ukpassthrough.MeleeWindups;
import dev.ukmc.ukpassthrough.MobReach;
import dev.ukmc.ukpassthrough.PlacedBlocks;
import dev.ukmc.ukpassthrough.UKPassthrough;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The mod's settings (round 25), changed in the "ULTRAKILL x Minecraft Settings" menu ({@link SettingsScreen}) and kept in
 * {@code config/ukpassthrough.json} in Minecraft's game folder. What ULTRAKILL needs of them goes to it as {"t":"settings",...}
 * on every connection and after every change (uk/src/Settings.cs). The menu's ULTRAKILL settings (volume, sensitivity, field
 * of view, screen shake) are ULTRAKILL's own: they come from it ({"t":"ukprefs"}) and go back one at a time ({"t":"ukpref"}).
 * "Reset to default" puts everything back as it was when this Minecraft started (the user's choice), ULTRAKILL's as it was
 * when that ULTRAKILL started ({"t":"ukreset"}). Client thread, unless said otherwise.
 */
final class ModSettings {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("ukpassthrough.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** A change is saved and sent this long after the last one (a slider being dragged changes many times a second). */
	private static final long SETTLE_NANOS = 500_000_000L;

	/** The settings as saved; missing ones keep these defaults. Read from other threads too (a stale read for a tick is harmless). */
	static final class Values {
		/** Blood stains on Minecraft's blocks (and ULTRAKILL's own stains). */
		boolean bloodStains = true;
		/** {@link Potato}: 0 off, 1 potato mode, 2 super potato mode. */
		int potato;
		/** Every ULTRAKILL weapon and arm in this mod, whatever the save has. */
		boolean allWeapons;
		/** No guns and no Sandbox arm in this mod (round 25d, the user's choice: V1 keeps its fists and the Whiplash). */
		boolean removeWeapons;
		/** Progression Mode (round 28, {@link Progression}): guns bought with P earned from experience, per world. */
		boolean progression;
		/** ULTRAKILL's cheats (and the Sandbox arm) outside creative too (round 29; off: only in creative and spectator). */
		boolean cheatsInSurvival;
		/** How hard ULTRAKILL's explosions break Minecraft's blocks (1: as each explosion's size; 0: not at all). */
		double explosionStrength = 1.0;
		/** V1's ground slam cracks and breaks blocks ({@link BlockBreaks}). */
		boolean slamBreaks = true;
		/** ULTRAKILL's weapons shatter glass and ice at once, and beams go through them. */
		boolean glassShatters = true;
		/** ULTRAKILL's enemies at this share of their own size (round 18's 80%). */
		double enemySize = 0.8;
		/** ULTRAKILL's enemies and Minecraft's mobs fight each other (rounds 18 and 19). */
		boolean fights = true;
		/** V1's weapons on Minecraft's mobs, times {@link MobHits#MOB_DAMAGE}. */
		double v1Damage = 1.0;
		/** The player's damage on V1, times {@link MobHits#V1_DAMAGE}. */
		double damageTaken = 1.0;
		/** The third-person cameras, in blocks from V1's eyes (they were the plugin's [ThirdPerson] config before round 25). */
		double shoulderBack = 1.6;
		double shoulderRight = 0.6;
		double shoulderUp = 0.2;
		double frontDistance = 2.2;
		/** The "ULTRAKILL: connected" line in the corner. */
		boolean statusLine = true;
		/** Minecraft's light on V1's arms and body and on ULTRAKILL's enemies (rounds 10 and 22). */
		boolean minecraftLight = true;
		/**
		 * Safe Building (round 30, the user's request, on by default; {@link PlacedBlocks}): the blocks the player placed stay through the mod's
		 * explosions, slams, shattering and gasoline's fire.
		 */
		boolean safeBuilding = true;
		/** The balance settings (round 30, {@link BalanceScreen}). Progression Mode's P for a point of experience. */
		int pPerXp = Progression.P_PER_XP;
		/** Progression Mode's gun prices, times (uk/src/Progression.cs). */
		double gunPrices = 1.0;
		/**
		 * Each kind of ULTRAKILL explosion's size on blocks, times (missing: the kind's own, {@link Explosions.Kind#size}), and the toughest
		 * blocks it breaks (missing: {@link Explosions#LIKE_TNT}), by {@link Explosions.Kind#key}.
		 */
		Map<String, Double> boomSize = new LinkedHashMap<>();
		Map<String, Integer> boomBreaks = new LinkedHashMap<>();
		/** A slam from this many blocks up breaks soft blocks at once. */
		double slamHeight = BlockBreaks.HIGH_SLAM;
		/** The slam's patch, times its size since round 24. */
		double slamSize = 1.0;
		/** How much a Knuckleblaster punch digs, times a second of Steve's bare hand. */
		double punchDigging = 1.0;
		/** The pickaxe the Jackhammer mines as ({@link Punches#JACKHAMMER_TOOLS}; 0: it doesn't mine). */
		int jackhammer = Punches.IRON;
		/** A parry's damage on mobs, times (uk/src/Mobs.cs). */
		double parryDamage = 1.0;
		/** ULTRAKILL's knockback on mobs, times. */
		double knockback = 1.0;
		/** How long a mob's melee hit on V1 winds up, in ticks (V1's time to parry it). */
		int windupTicks = MeleeWindups.WINDUP_TICKS;
		/** How far a mob's melee reaches V1, as a share of how far it reaches Steve. */
		double mobReach = MobReach.V1_SHARE;
		/** ULTRAKILL's enemies' hits on mobs, times. */
		double enemyDamage = 1.0;
		/** Mobs' hits on ULTRAKILL's enemies, times. */
		double mobsOnEnemies = 1.0;
		/**
		 * The mod's own loadout (round 27), chosen at ULTRAKILL's shop terminal (round 27b, uk/src/Terminal.cs): each variant's and arm's
		 * state by ULTRAKILL's key ("rev0" to "rock2", "arm0" to "arm2"; 0 unequipped, 1 equipped, 2 alternate), and each weapon's
		 * variant order ("rev" to "rock", as "1324"). Both empty: the player's own ULTRAKILL loadout.
		 */
		Map<String, Integer> loadout = new LinkedHashMap<>();
		Map<String, String> loadoutOrder = new LinkedHashMap<>();
		/**
		 * The guns' colours chosen at the terminal's colour pages (round 29: only in this mod), by ULTRAKILL's preference ("gunColorType.1",
		 * "gunColorPreset.2.a", "gunColor.3.1.r", ...). Empty: the player's own ULTRAKILL colours.
		 */
		Map<String, JsonElement> gunColors = new LinkedHashMap<>();
		/** Minecraft's options as they were before potato mode changed them, and what it changed them to ({@link Potato}). */
		Map<String, JsonElement> potatoBefore = new LinkedHashMap<>();
		Map<String, JsonElement> potatoSet = new LinkedHashMap<>();

		Values copy() {
			Values v = GSON.fromJson(GSON.toJsonTree(this), Values.class);
			return v == null ? new Values() : v;
		}

		double boomSize(final Explosions.Kind kind) {
			Double size = this.boomSize.get(kind.key());
			return size == null ? kind.size() : size;
		}

		int boomBreaks(final Explosions.Kind kind) {
			Integer breaks = this.boomBreaks.get(kind.key());
			return breaks == null ? Explosions.LIKE_TNT : breaks;
		}
	}

	private static Values values = new Values();
	/** As they were when this Minecraft started, for "Reset to default". */
	private static Values atStart = new Values();
	/** ULTRAKILL's own settings as it last told (null until it has: it isn't connected). Link thread writes it. */
	private static volatile JsonObject ukPrefs;
	/** Counts what ULTRAKILL told of them (not the menu's own changes, which it doesn't echo): an open menu shows the new values. */
	private static volatile int ukVersion;
	/** ULTRAKILL's settings changed in the menu and not sent yet, by the menu's name for each. */
	private static final Map<String, Double> UK_PENDING = new LinkedHashMap<>();
	private static long changedAt;
	private static boolean dirty;
	private static boolean sendPending;
	private static int sentGeneration = -1;
	/**
	 * How many of ULTRAKILL's loadout changes on this connection the settings kept have ("seq" of its last {"t":"loadoutset"}), sent back
	 * as "lseq": ULTRAKILL leaves settings sent before the latest alone (round 29b: a stale copy made the weapons again mid-drag).
	 */
	private static int ukLoadoutSeq;

	private ModSettings() {
	}

	static Values get() {
		return values;
	}

	/** At start-up. */
	static void load() {
		if (Files.isRegularFile(FILE)) {
			try (Reader r = Files.newBufferedReader(FILE)) {
				Values v = GSON.fromJson(r, Values.class);
				if (v != null) {
					values = v;
				}
			} catch (IOException | JsonParseException e) {
				UKPassthrough.LOG.warn("couldn't read {} ({}): the settings start from their defaults", FILE, e.toString());
			}
		}

		if (values.potatoBefore == null || values.potatoSet == null) {
			values.potatoBefore = new LinkedHashMap<>();
			values.potatoSet = new LinkedHashMap<>();
		}

		if (values.loadout == null || values.loadoutOrder == null) {
			values.loadout = new LinkedHashMap<>();
			values.loadoutOrder = new LinkedHashMap<>();
		}

		if (values.gunColors == null) {
			values.gunColors = new LinkedHashMap<>();
		}

		if (values.boomSize == null || values.boomBreaks == null) {
			values.boomSize = new LinkedHashMap<>();
			values.boomBreaks = new LinkedHashMap<>();
		}

		atStart = values.copy();
		applyServerSide();
		UKPassthrough.LOG.info("settings: blood stains {}, potato mode {}, all weapons {}", values.bloodStains ? "on" : "off", Potato.name(values.potato), values.allWeapons ? "on" : "off");
	}

	/** Something in the menu changed: saved and sent to ULTRAKILL once it settles. */
	static void changed() {
		dirty = true;
		sendPending = true;
		changedAt = System.nanoTime();
	}

	/** One of ULTRAKILL's own settings changed in the menu ("vol", "sens", "fov", "shake"). */
	static void setUk(final String name, final double value) {
		JsonObject p = ukPrefs;
		if (p != null) {
			JsonObject copy = p.deepCopy();
			copy.addProperty(name, value);
			ukPrefs = copy;
		}

		UK_PENDING.put(name, value);
	}

	/** ULTRAKILL's setting by the menu's name, or NaN while ULTRAKILL hasn't told (it isn't connected). */
	static double uk(final String name) {
		JsonObject p = ukPrefs;
		UkLink link = UkLink.get();
		return p == null || !p.has(name) || link == null || !link.connected() ? Double.NaN : p.get(name).getAsDouble();
	}

	static int ukVersion() {
		return ukVersion;
	}

	/** From the link thread: {"t":"ukprefs","vol":..,"sens":..,"fov":..,"shake":..}. */
	static void onUkPrefs(final JsonObject m) {
		ukPrefs = m;
		ukVersion++;
	}

	/** Whether the mod has a loadout (or gun colours) of its own (otherwise it's the player's ULTRAKILL one). */
	static boolean customLoadout() {
		return !values.loadout.isEmpty() || !values.loadoutOrder.isEmpty() || !values.gunColors.isEmpty();
	}

	/** "Use My ULTRAKILL Loadout": the mod's own loadout and gun colours go, ULTRAKILL's terminals' are used again. */
	static void clearLoadout() {
		values.loadout.clear();
		values.loadoutOrder.clear();
		values.gunColors.clear();
		changed();
	}

	/**
	 * From the link thread: {"t":"loadoutset","loadout":{...},"order":{...},"colors":{...}}, the loadout and gun colours as ULTRAKILL's
	 * shop terminal left them (the whole of them), kept here and sent back with the settings (ULTRAKILL sees they're the same and does
	 * nothing more).
	 */
	static void onLoadoutSet(final JsonObject m) {
		Map<String, Integer> loadout = new LinkedHashMap<>();
		Map<String, String> order = new LinkedHashMap<>();
		Map<String, JsonElement> colors = new LinkedHashMap<>();
		m.getAsJsonObject("loadout").entrySet().forEach(e -> loadout.put(e.getKey(), e.getValue().getAsInt()));
		m.getAsJsonObject("order").entrySet().forEach(e -> order.put(e.getKey(), e.getValue().getAsString()));
		if (m.has("colors")) {
			m.getAsJsonObject("colors").entrySet().forEach(e -> colors.put(e.getKey(), e.getValue()));
		}

		int seq = m.has("seq") ? m.get("seq").getAsInt() : 0;
		net.minecraft.client.Minecraft.getInstance().execute(() -> {
			values.loadout = loadout;
			values.loadoutOrder = order;
			values.gunColors = colors;
			// with the values it belongs to: settings sent from here on have this change
			ukLoadoutSeq = Math.max(ukLoadoutSeq, seq);
			changed();
			UKPassthrough.LOG.info("settings: the loadout from ULTRAKILL's terminal kept ({} variants and arms, {} orders, {} gun colour settings)", loadout.size(), order.size(), colors.size());
		});
	}

	/** Potato mode to {@code level}: Minecraft's options change at once ({@link Potato}), ULTRAKILL's when it hears. */
	static void setPotato(final int level) {
		if (level != values.potato) {
			Potato.change(values, level);
			values.potato = level;
			// the backup of Minecraft's options is saved at once: it must survive a crash
			save();
		}

		changed();
	}

	/** The balance menu's "Reset to Default": the balance settings as the mod has them (round 30), the rest as they are. */
	static void resetBalance() {
		Values d = new Values();
		Values v = values;
		v.pPerXp = d.pPerXp;
		v.gunPrices = d.gunPrices;
		v.boomSize.clear();
		v.boomBreaks.clear();
		v.slamHeight = d.slamHeight;
		v.slamSize = d.slamSize;
		v.punchDigging = d.punchDigging;
		v.jackhammer = d.jackhammer;
		v.parryDamage = d.parryDamage;
		v.knockback = d.knockback;
		v.windupTicks = d.windupTicks;
		v.mobReach = d.mobReach;
		v.enemyDamage = d.enemyDamage;
		v.mobsOnEnemies = d.mobsOnEnemies;
		changed();
		UKPassthrough.LOG.info("settings: the balance settings back to the mod's own");
	}

	/** "Reset to default": everything as it was when this Minecraft started; ULTRAKILL's own as when it started. */
	static void reset() {
		setPotato(atStart.potato);
		Values v = atStart.copy();
		v.potato = values.potato;
		v.potatoBefore = values.potatoBefore;
		v.potatoSet = values.potatoSet;
		values = v;
		UK_PENDING.clear();
		UkLink link = UkLink.get();
		if (link != null) {
			link.send(UkLink.type("ukreset"));
		}

		save();
		changed();
		UKPassthrough.LOG.info("settings: back to how they were when the game started");
	}

	/** Every client tick: a new ULTRAKILL connection gets the settings; changes are saved and sent once they settle. */
	static void tick() {
		applyServerSide();
		UkLink link = UkLink.get();
		boolean connected = link != null && link.connected();
		if (connected && link.generation() != sentGeneration) {
			sentGeneration = link.generation();
			sendPending = true;
			// a new ULTRAKILL connection counts its changes from 0
			ukLoadoutSeq = 0;
		}

		if (!UK_PENDING.isEmpty()) {
			if (connected) {
				for (Map.Entry<String, Double> e : UK_PENDING.entrySet()) {
					JsonObject m = UkLink.type("ukpref");
					m.addProperty("k", e.getKey());
					m.addProperty("v", e.getValue());
					link.send(m);
				}
			}

			UK_PENDING.clear();
		}

		if (sendPending && connected) {
			sendPending = false;
			send(link);
		}

		if (dirty && System.nanoTime() - changedAt > SETTLE_NANOS) {
			save();
		}
	}

	/** The menu closed: whatever changed is saved now. */
	static void flush() {
		if (dirty) {
			save();
		}
	}

	/** What the server's side of the mod (mob windups and reach, Safe Building) reads of the settings. */
	private static void applyServerSide() {
		Values v = values;
		PlacedBlocks.on = v.safeBuilding;
		MeleeWindups.windupTicks = Math.clamp(v.windupTicks, 0, 40);
		MobReach.v1Share = Math.clamp(v.mobReach, 0.0, 2.0);
	}

	private static void send(final UkLink link) {
		Values v = values;
		JsonObject m = UkLink.type("settings");
		m.addProperty("stains", v.bloodStains);
		m.addProperty("potato", v.potato);
		m.addProperty("weapons", v.allWeapons);
		m.addProperty("noweapons", v.removeWeapons);
		m.addProperty("cheats", v.cheatsInSurvival);
		m.addProperty("boom", v.explosionStrength);
		m.addProperty("glass", v.glassShatters);
		m.addProperty("size", v.enemySize);
		m.addProperty("fights", v.fights);
		m.addProperty("back", v.shoulderBack);
		m.addProperty("right", v.shoulderRight);
		m.addProperty("up", v.shoulderUp);
		m.addProperty("front", v.frontDistance);
		m.addProperty("light", v.minecraftLight);
		m.addProperty("prices", v.gunPrices);
		m.addProperty("parry", v.parryDamage);
		m.addProperty("lseq", ukLoadoutSeq);
		if (!v.loadout.isEmpty() || !v.loadoutOrder.isEmpty() || !v.gunColors.isEmpty()) {
			m.add("loadout", GSON.toJsonTree(v.loadout));
			m.add("order", GSON.toJsonTree(v.loadoutOrder));
			m.add("colors", GSON.toJsonTree(v.gunColors));
		}

		link.send(m);
	}

	private static void save() {
		dirty = false;
		try {
			Files.createDirectories(FILE.getParent());
			Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp)) {
				GSON.toJson(values, w);
			}

			Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			UKPassthrough.LOG.warn("couldn't save {}: {}", FILE, e.toString());
		}
	}
}
