package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonObject;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/**
 * ULTRAKILL's impact frames on Minecraft's world (round 33, a player's report: the Jackhammer's freeze stopped ULTRAKILL and not
 * Minecraft). A parry, a heavy hit or a Jackhammer strike stops ULTRAKILL's time for a moment (TimeController.HitStop and
 * TrueStop); it says how long ({"t":"stop","ms":n}, uk/src/HitStops.cs), and Minecraft's world freezes as long, as /tick freeze
 * freezes it: mobs, projectiles and items stop where they are, and are drawn there (the client's own tick rate manager and frame
 * timer are frozen at once, though Minecraft itself only applies a freeze at its next tick: they stop in the frame the message comes,
 * at most a tick's motion on, rather than gliding on for up to 50 ms and jumping back; the server's too, so they don't move on). The player isn't
 * frozen (V1 drives it, and V1 stops with ULTRAKILL). A freeze the player set with /tick freeze is left alone. Client thread, but
 * {@link #onStop} (link thread).
 */
final class HitStops {
	/** No freeze lasts longer than this (ms), whatever is asked: a lost "go on" mustn't leave the world stopped. */
	private static final long LONGEST = 1000L;

	/** Until when (System.nanoTime) the world stays frozen; 0: no freeze asked for. Link thread writes, client thread reads. */
	private static volatile long until;
	/** This froze the world (and must let it go). */
	private static boolean frozen;
	private static IntegratedServer frozenOn;
	/** Development (demo 45): impact frames left to ULTRAKILL alone, as before round 33. */
	static volatile boolean off;

	private HitStops() {
	}

	/** From the link thread: ULTRAKILL's time stops for {@code ms}. */
	static void onStop(final JsonObject m) {
		long ms = Math.clamp(m.get("ms").getAsLong(), 0L, LONGEST);
		if (off || ms <= 0L) {
			return;
		}

		long end = System.nanoTime() + ms * 1_000_000L;
		if (end > until) {
			until = end;
		}

		// in the frame it comes, not at the next tick
		Minecraft.getInstance().execute(() -> frame(Minecraft.getInstance()));
	}

	/** Every frame (and as a stop comes): freezes or lets go. */
	static void frame(final Minecraft minecraft) {
		boolean wanted = until != 0L && System.nanoTime() < until && Control.driving() && minecraft.level != null;
		if (wanted == frozen) {
			return;
		}

		if (wanted) {
			// the player's own /tick freeze: not this one's to start or end
			if (minecraft.level.tickRateManager().isFrozen()) {
				until = 0L;
				return;
			}

			frozen = true;
			frozenOn = minecraft.getSingleplayerServer();
			minecraft.level.tickRateManager().setFrozen(true);
			// now, not at the next tick (no /tick step is under way: the world wasn't frozen)
			minecraft.level.tickRateManager().tick();
			if (minecraft.getDeltaTracker() instanceof DeltaTracker.Timer timer) {
				timer.updateFrozenState(true);
			}

			IntegratedServer server = frozenOn;
			if (server != null) {
				server.execute(() -> server.tickRateManager().setFrozen(true));
			}

			return;
		}

		until = 0L;
		frozen = false;
		if (minecraft.level != null) {
			minecraft.level.tickRateManager().setFrozen(false);
			minecraft.level.tickRateManager().tick();
		}

		if (minecraft.getDeltaTracker() instanceof DeltaTracker.Timer timer) {
			timer.updateFrozenState(false);
		}

		IntegratedServer server = frozenOn;
		frozenOn = null;
		if (server != null && server == minecraft.getSingleplayerServer()) {
			server.execute(() -> server.tickRateManager().setFrozen(false));
		}
	}

	/** For the dev demo's log. */
	static boolean frozen() {
		return frozen;
	}
}
