package dev.ukmc.ukpassthrough.client;

import dev.ukmc.ukpassthrough.UKPassthrough;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A launched mob (the ground slam's shockwave, a rocket landing beside it: MobHits.onLaunch) is seen going up at once (round 18).
 * Minecraft sends a mob's place only every 3 ticks, and the client then plays the 3 ticks it missed over the next 3 (an entity
 * type's update interval, SteppedInterpolationHandler), so a launch showed 100 to 300 ms after the server pushed the mob. For 2
 * seconds after a launch: the server sends that mob's place every tick (needsSync), and the client shows the newest place as soon
 * as it arrives instead of catching up. The flight is shown from the moment ULTRAKILL's message comes: the client works it out as
 * the server will move the mob (Minecraft's gravity and air drag), until the push is spent, then waits there for the server's
 * places to catch up (they come two ticks behind) and follows them. Measured (demo 21): the mob was first drawn higher 213 ms after
 * ULTRAKILL's shockwave reached it before, 10 ms now.
 */
final class Launches {
	/** How long a launch is followed closely (ticks). */
	private static final int FOLLOW_TICKS = 40;
	/** Minecraft's gravity and air drag for a mob (LivingEntity.travel). */
	private static final double GRAVITY = 0.08;
	private static final double DRAG_UP = 0.98;
	private static final double DRAG_ACROSS = 0.91;
	/** The flight is worked out while it still goes this share of the push's speed the push's way, and for at most so many ticks. */
	private static final double SPENT = 0.1;
	private static final int MOST_PREDICTED = 12;
	/** How long the worked-out flight waits at its end for the server's places to catch up (ticks), and how near is caught up (blocks). */
	private static final int MOST_HELD = 4;
	private static final double NEAR = 0.15;
	/** A mob on the ground still has a tick of gravity in its motion (Minecraft keeps pulling it down), which a push adds to. */
	private static final double ON_GROUND_Y = -GRAVITY * DRAG_UP;
	/** Server thread: launched mobs by id, and the server tick until which their place is sent every tick. */
	private static final Map<Integer, Integer> SYNCING = new HashMap<>();
	/** Client thread: launched mobs being shown, by id. */
	private static final Map<Integer, Shown> SHOWN = new HashMap<>();
	/** For the dev demo's before-and-after: false is the old way (the push alone). */
	static volatile boolean enabled = true;
	private static int clientTicks;

	/** Client thread: a launched mob being shown. */
	private static final class Shown {
		/** The push's direction (unit). */
		final Vec3 along;
		final int until;
		/**
		 * The flight worked out as the server will move the mob, a place a tick from where it was seen (the first), until the push is
		 * spent or it hits something.
		 */
		final List<Vec3> path = new ArrayList<>();
		/** How far into the flight (ticks) the mob is shown at the end of this tick: the flight's clock starts when it was heard of. */
		double t;
		int heldTicks;
		/** Frames drawn since it was shown (counted with the HUD), and whether a tick has ended since. */
		int frames;
		boolean ticked;
		/** The server's last place seen, while it's being waited for. */
		Vec3 lastServer;
		/** The server's places are shown now. */
		boolean following;

		Shown(final Vec3 push, final int until) {
			this.along = push.normalize();
			this.until = until;
		}

		/** Where the flight has it {@code t} ticks in (between the ticks' places; before the start, on along its first tick's line). */
		Vec3 at(final double t) {
			if (t <= 0.0) {
				return this.path.get(0).add(this.path.get(1).subtract(this.path.get(0)).scale(t));
			}

			int i = (int) Math.floor(t);
			if (i >= this.path.size() - 1) {
				return this.path.getLast();
			}

			return this.path.get(i).add(this.path.get(i + 1).subtract(this.path.get(i)).scale(t - i));
		}

		boolean over() {
			return this.t >= this.path.size() - 1;
		}
	}

	private Launches() {
	}

	/** Server thread, right after the push: its place goes out every tick for a while. */
	static void pushed(final MinecraftServer server, final LivingEntity mob) {
		if (enabled) {
			SYNCING.put(mob.getId(), server.getTickCount() + FOLLOW_TICKS);
		}
	}

	/** Every server tick (after the level's): set again for the tracker's next pass, until it's over or the mob is gone. */
	static void serverTick(final MinecraftServer server) {
		if (SYNCING.isEmpty()) {
			return;
		}

		int now = server.getTickCount();
		ServerLevel level = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().getFirst().level();
		for (Iterator<Map.Entry<Integer, Integer>> i = SYNCING.entrySet().iterator(); i.hasNext();) {
			Map.Entry<Integer, Integer> e = i.next();
			Entity mob = level == null ? null : level.getEntity(e.getKey());
			if (mob == null || !mob.isAlive() || now > e.getValue()) {
				i.remove();
			} else {
				mob.needsSync = true;
			}
		}
	}

	/**
	 * Client thread, as soon as the launch is heard of: the flight is worked out and starts now. The rest of this tick shows its
	 * first part: Minecraft draws an entity between where it was at the tick's start and where it is, so both are set for it to
	 * be where it's seen now and go at the push's speed.
	 */
	static void show(final int id, final Vec3 push, final long heard) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!enabled || minecraft.level == null || !(minecraft.level.getEntity(id) instanceof LivingEntity mob) || push.lengthSqr() < 1.0E-6) {
			return;
		}

		// what Minecraft was still playing of its last places is dropped: the mob goes up from where it's seen
		mob.getInterpolation().cancel();
		Shown s = new Shown(push, clientTicks + FOLLOW_TICKS);
		work(minecraft, mob, s, mob.onGround() ? push.add(0.0, ON_GROUND_Y, 0.0) : push, push.length());
		if (s.path.size() < 2) {
			return;
		}

		// this frame's: Minecraft has already counted the ticks it runs before drawing it
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		s.t = 1.0 - partial;
		place(mob, s);
		SHOWN.put(id, s);
		UKPassthrough.LOG.info("launch of mob {} shown {} ms after the link heard of it", id, System.currentTimeMillis() - heard);
	}

	/** The mob at the flight's place for the end of this tick, and at the one a tick before for its start (what's drawn in between). */
	private static void place(final Entity mob, final Shown s) {
		Vec3 old = s.at(s.t - 1.0);
		mob.setPos(s.at(s.t));
		mob.xo = mob.xOld = old.x;
		mob.yo = mob.yOld = old.y;
		mob.zo = mob.zOld = old.z;
	}

	/** Every frame drawn. */
	static void frame() {
		for (Shown s : SHOWN.values()) {
			s.frames++;
		}
	}

	/** The flight, a place a tick, as the server moves the mob (LivingEntity.travel in the air), while the push lasts. */
	private static void work(final Minecraft minecraft, final Entity mob, final Shown s, final Vec3 start, final double speed) {
		Vec3 at = mob.position();
		Vec3 v = start;
		s.path.add(at);
		AABB box = mob.getBoundingBox();
		while (s.path.size() <= MOST_PREDICTED && v.dot(s.along) > SPENT * speed) {
			AABB next = box.move(at.add(v).subtract(mob.position()));
			if (!minecraft.level.noCollision(mob, next)) {
				break;
			}

			at = at.add(v);
			s.path.add(at);
			v = new Vec3(v.x * DRAG_ACROSS, (v.y - GRAVITY) * DRAG_UP, v.z * DRAG_ACROSS);
		}
	}

	/**
	 * Every client tick, after the entities': a launched mob goes on with its worked-out flight while the push lasts, then waits
	 * there for the server's places to catch up, then is put where the server last said (not caught up over 3 ticks).
	 */
	static void clientTick(final Minecraft minecraft) {
		clientTicks++;
		if (SHOWN.isEmpty()) {
			return;
		}

		for (Iterator<Map.Entry<Integer, Shown>> i = SHOWN.entrySet().iterator(); i.hasNext();) {
			Map.Entry<Integer, Shown> e = i.next();
			Shown s = e.getValue();
			Entity mob = minecraft.level == null ? null : minecraft.level.getEntity(e.getKey());
			if (mob == null || !mob.isAlive() || clientTicks > s.until) {
				i.remove();
				continue;
			}

			InterpolationHandler interpolation = mob.getInterpolation();
			Vec3 server = interpolation.hasActiveInterpolation() ? interpolation.target().position() : null;
			interpolation.cancel();
			if (!s.ticked) {
				s.ticked = true;
				if (s.frames == 0) {
					// a tick in the frame it was shown in, before that frame was drawn: it made the shown start its own and skipped it
					place(mob, s);
					continue;
				}
			}

			if (!s.following) {
				s.t += 1.0;
				Vec3 shown = s.at(s.t);
				// caught up: as far along as the shown flight, or already on its way back down
				boolean caughtUp = server != null && (server.subtract(shown).dot(s.along) >= -NEAR || (s.lastServer != null && server.y < s.lastServer.y && s.over()));
				if (server != null) {
					s.lastServer = server;
				}

				if (caughtUp || (s.over() && ++s.heldTicks > MOST_HELD)) {
					s.following = true;
				} else {
					mob.setPos(shown);
					continue;
				}
			}

			if (server != null) {
				mob.setPos(server);
			}
		}
	}

	static String describe() {
		return String.format(Locale.ROOT, "%d followed on the server, %d shown", SYNCING.size(), SHOWN.size());
	}
}
