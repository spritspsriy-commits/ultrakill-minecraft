package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ukmc.ukpassthrough.UKPassthrough;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Streams the blocks around V1 (or the player, until ULTRAKILL reports V1) to ULTRAKILL, one 16x16x16 section
 * per binary message, and resends a section the moment one of its blocks changes. Client thread only.
 * <ul>
 * <li>{"t":"world","id":n,"dim":"minecraft:overworld","minY":-64,"maxY":320,"at":[x,y,z]}: a new world (dim null: none)</li>
 * <li>{"t":"player","p":[x,y,z],"r":[yaw,pitch],"sky":blocks}: the player's feet, 10 times a second, and how far it could rise</li>
 * <li>binary section messages ({@link SectionEncoder})</li>
 * <li>{"t":"unload","s":[sx,sy,sz, ...]}: sections ULTRAKILL can forget (too far away)</li>
 * </ul>
 */
public final class BlockStream {
	/** Sections kept around the centre: 4 = 64 blocks out horizontally, 2 = 32 blocks up and down. */
	private static final int RADIUS = Integer.getInteger("ukpassthrough.radius", 4);
	private static final int VRADIUS = Integer.getInteger("ukpassthrough.vradius", 2);
	/** New sections encoded per tick; changed sections always go out at once. */
	private static final int BUDGET = 32;
	/** The free sky above the player is looked at this far up (blocks): V1 falls in from 30 at most. */
	private static final int SKY_CHECKED = 32;

	private static final LongOpenHashSet sent = new LongOpenHashSet();
	private static final LongOpenHashSet dirty = new LongOpenHashSet();
	private static volatile ClientLevel announced;
	private static boolean announcedNone;
	private static int linkGeneration = -1;
	private static volatile int worldId;
	private static int ticks;

	private BlockStream() {
	}

	/** How many sections ULTRAKILL currently has from us. */
	public static int sentCount() {
		return sent.size();
	}

	/** A block changed on the client (ClientLevel.sendBlockUpdated). */
	public static void blockChanged(final int x, final int y, final int z) {
		mark(key(x >> 4, y >> 4, z >> 4));
		if ((y & 15) == 0) {
			// water's height below depends on whether water is above it: the section underneath may change too
			mark(key(x >> 4, (y >> 4) - 1, z >> 4));
		}
	}

	/** A whole section changed (ClientLevel.setSectionDirtyWithNeighbors). */
	public static void sectionChanged(final int sx, final int sy, final int sz) {
		mark(key(sx, sy, sz));
	}

	private static void mark(final long key) {
		if (sent.contains(key)) {
			dirty.add(key);
		}
	}

	/** The world (or dimension) last announced to ULTRAKILL; its camera messages carry the one they belong to. */
	static int worldId() {
		return worldId;
	}

	/** The level last announced to ULTRAKILL (null before the first). */
	static ClientLevel announcedLevel() {
		return announced;
	}

	static void tick(final Minecraft minecraft) {
		UkLink link = UkLink.get();
		if (!link.connected()) {
			forget();
			linkGeneration = -1;
			// nobody to say V1 came back
			UKPassthrough.v1Dead = false;
			return;
		}

		if (link.generation() != linkGeneration) {
			// a new ULTRAKILL connection knows nothing yet: announce the world again and resend everything
			linkGeneration = link.generation();
			forget();
		}

		ClientLevel level = minecraft.level;
		Player player = minecraft.player;
		if (level == null || player == null) {
			if (!announcedNone) {
				JsonObject none = UkLink.type("world");
				none.addProperty("id", ++worldId);
				none.add("dim", null);
				link.send(none);
				announcedNone = true;
			}

			forget();
			announcedNone = true;
			return;
		}

		if (level != announced) {
			forget();
			announced = level;
			// V1's pose belongs to the old world: until ULTRAKILL puts V1 in this one, Steve mustn't follow it back
			V1State.clear();
			// and gasoline on its mobs (the stand-ins go with the world there)
			Oil.clear();
			// a world opening brings V1 back to life (uk/src/Arena.cs), a dimension change happens to a living one
			UKPassthrough.v1Dead = false;
			announcedNone = false;
			JsonObject world = UkLink.type("world");
			world.addProperty("id", ++worldId);
			world.addProperty("dim", level.dimension().identifier().toString());
			world.addProperty("minY", level.getMinY());
			world.addProperty("maxY", level.getMaxY());
			// where the player is: ULTRAKILL puts its origin there before the first blocks arrive
			JsonArray at = new JsonArray();
			at.add(player.getX());
			at.add(player.getY());
			at.add(player.getZ());
			world.add("at", at);
			link.send(world);
		}

		if ((ticks++ & 1) == 0) {
			JsonObject p = UkLink.type("player");
			JsonArray at = new JsonArray();
			at.add(player.getX());
			at.add(player.getY());
			at.add(player.getZ());
			p.add("p", at);
			JsonArray r = new JsonArray();
			r.add(Mth.positiveModulo(player.getYRot(), 360.0F));
			r.add(player.getXRot());
			p.add("r", r);
			p.addProperty("sky", skyAbove(level, player));
			link.send(p);
		}

		boolean v1 = V1State.fresh();
		int csx = floor(v1 ? V1State.x() : player.getX()) >> 4;
		int csy = floor(v1 ? V1State.y() : player.getY()) >> 4;
		int csz = floor(v1 ? V1State.z() : player.getZ()) >> 4;
		int minSy = level.getMinSectionY();
		int maxSy = level.getMaxSectionY();

		// forget what's far behind
		LongArrayList unload = new LongArrayList();
		for (LongIterator it = sent.iterator(); it.hasNext(); ) {
			long k = it.nextLong();
			if (Math.abs(kx(k) - csx) > RADIUS + 1 || Math.abs(kz(k) - csz) > RADIUS + 1 || Math.abs(ky(k) - csy) > VRADIUS + 1) {
				it.remove();
				dirty.remove(k);
				unload.add(k);
			}
		}

		if (!unload.isEmpty()) {
			JsonObject m = UkLink.type("unload");
			JsonArray s = new JsonArray();
			for (long k : unload) {
				s.add(kx(k));
				s.add(ky(k));
				s.add(kz(k));
			}

			m.add("s", s);
			link.send(m);
		}

		// changed sections first, all of them: a block placed or broken must reach ULTRAKILL straight away
		if (!dirty.isEmpty()) {
			for (LongIterator it = dirty.iterator(); it.hasNext(); ) {
				long k = it.nextLong();
				LevelChunk chunk = loadedChunk(level, kx(k), kz(k));
				if (chunk != null) {
					link.sendBinary(SectionEncoder.encode(level, chunk, kx(k), ky(k), kz(k)));
				}
			}

			dirty.clear();
		}

		// then new sections, nearest first
		LongArrayList wanted = new LongArrayList();
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				if (loadedChunk(level, csx + dx, csz + dz) == null) {
					continue;
				}

				for (int dy = -VRADIUS; dy <= VRADIUS; dy++) {
					int sy = csy + dy;
					long k = key(csx + dx, sy, csz + dz);
					if (sy >= minSy && sy <= maxSy && !sent.contains(k)) {
						wanted.add(k);
					}
				}
			}
		}

		if (wanted.isEmpty()) {
			return;
		}

		wanted.sort((a, b) -> Integer.compare(distance(a, csx, csy, csz), distance(b, csx, csy, csz)));
		for (int i = 0; i < Math.min(BUDGET, wanted.size()); i++) {
			long k = wanted.getLong(i);
			link.sendBinary(SectionEncoder.encode(level, loadedChunk(level, kx(k), kz(k)), kx(k), ky(k), kz(k)));
			sent.add(k);
		}
	}

	/**
	 * How many whole blocks the player could rise from where it stands before touching anything, up to {@link #SKY_CHECKED}: where
	 * ULTRAKILL lets V1 fall in from when a world opens (round 29), never through a roof.
	 */
	private static int skyAbove(final ClientLevel level, final Player player) {
		int up = 0;
		while (up < SKY_CHECKED && level.noCollision(player, player.getBoundingBox().move(0.0, up + 1.0, 0.0))) {
			up++;
		}

		return up;
	}

	private static void forget() {
		sent.clear();
		dirty.clear();
		announced = null;
		announcedNone = false;
	}

	private static LevelChunk loadedChunk(final ClientLevel level, final int cx, final int cz) {
		return level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
	}

	private static int distance(final long k, final int x, final int y, final int z) {
		int dx = kx(k) - x, dy = ky(k) - y, dz = kz(k) - z;
		return dx * dx + 2 * dy * dy + dz * dz;
	}

	private static int floor(final double v) {
		return (int) Math.floor(v);
	}

	/** Packs section coordinates: 22 bits x, 20 bits y, 22 bits z. */
	static long key(final int sx, final int sy, final int sz) {
		return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFFL);
	}

	static int kx(final long k) {
		return (int) (k << 0 >> 42);
	}

	static int kz(final long k) {
		return (int) (k << 22 >> 42);
	}

	static int ky(final long k) {
		return (int) (k << 44 >> 44);
	}
}
