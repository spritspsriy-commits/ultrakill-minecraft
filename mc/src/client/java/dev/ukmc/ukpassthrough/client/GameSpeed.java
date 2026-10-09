package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonObject;
import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.ServerTickRateManager;

/**
 * ULTRAKILL's game speed assist on Minecraft's world (round 25b, the user's request that ULTRAKILL's assist options work in
 * Minecraft). With major assists on, ULTRAKILL slows its own time, and so V1; it says how much ({"t":"speed","s":share},
 * uk/src/Assists.cs), and while V1 plays Minecraft's world ticks that much slower too (the integrated server's tick rate, as
 * /tick rate sets it; the client follows the server's rate). Back to 20 a second when V1 mode is off, ULTRAKILL goes, or the
 * assist is off, unless something else (the player's own /tick rate) changed the rate meanwhile. Client thread.
 */
final class GameSpeed {
	private static final float NORMAL = 20.0F;

	/** ULTRAKILL's game speed as it last said (link thread). */
	private static volatile float ultrakill = 1.0F;
	/** The tick rate this set on the current server, or 0 if it set none. */
	private static float applied;
	private static IntegratedServer appliedOn;

	private GameSpeed() {
	}

	/** From the link thread. */
	static void onSpeed(final JsonObject m) {
		ultrakill = Math.clamp(m.get("s").getAsFloat(), 0.1F, 2.0F);
	}

	/** Every client tick. */
	static void tick(final Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != appliedOn) {
			// a new world: its tick rate is its own
			appliedOn = server;
			applied = 0.0F;
		}

		if (server == null) {
			return;
		}

		float speed = Control.driving() ? ultrakill : 1.0F;
		float wanted = speed == 1.0F ? 0.0F : NORMAL * speed;
		if (wanted == applied) {
			return;
		}

		float was = applied;
		applied = wanted;
		server.execute(() -> {
			ServerTickRateManager rate = server.tickRateManager();
			if (wanted == 0.0F) {
				// back to normal, if the rate is still the one set here
				if (Math.abs(rate.tickrate() - was) < 0.01F) {
					rate.setTickRate(NORMAL);
					UKPassthrough.LOG.info("game speed: Minecraft's world at full speed again");
				}
			} else {
				rate.setTickRate(wanted);
				UKPassthrough.LOG.info("game speed: ULTRAKILL's game speed assist {}%: Minecraft's world at {} ticks a second", Math.round(speed * 100.0F), wanted);
			}
		});
	}
}
