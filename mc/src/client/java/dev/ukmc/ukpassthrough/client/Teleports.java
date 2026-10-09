package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ukmc.ukpassthrough.UKPassthrough;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * The server moved the player within the same world: an end gateway, an ender pearl, /tp. While V1 drives, Steve
 * would be pulled straight back to V1's feet (and the server would take that as the player walking back). V1 goes
 * there instead: ULTRAKILL gets {"t":"moved","id":n,"p":[x,y,z],"r":[yaw,pitch]}, holds V1 until the ground there has
 * arrived and puts it down, and its camera messages carry that id ("m") from then on. Cameras without it were sent
 * before ULTRAKILL heard of the move and don't count. A change of dimension is announced as a new world instead
 * ({@link BlockStream}). Client thread.
 */
public final class Teleports {
	/** Server position updates closer than this to V1's feet aren't moves (a block, squared). */
	private static final double MOVED_SQ = 1.0;

	private static volatile int id;
	private static int linkGeneration = -1;

	private Teleports() {
	}

	/** The last move announced to ULTRAKILL (0 before the first since it connected). */
	static int id() {
		return id;
	}

	/** Every client tick: a new ULTRAKILL connection starts counting again. */
	static void tick() {
		int generation = UkLink.get().generation();
		if (generation != linkGeneration) {
			linkGeneration = generation;
			id = 0;
		}
	}

	/** After the client applied a position from the server (ClientPacketListener.handleMovePlayer). */
	public static void onServerMoved() {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		V1State.Pose p = V1State.live();
		// not driving, or a new dimension (announced as a new world as soon as the block stream sees it)
		if (player == null || p == null || !Control.driving() || minecraft.level != BlockStream.announcedLevel()) {
			return;
		}

		if (player.distanceToSqr(p.fx(), p.fy(), p.fz()) < MOVED_SQ) {
			return;
		}

		id++;
		V1State.clear();
		JsonObject m = UkLink.type("moved");
		m.addProperty("id", id);
		JsonArray at = new JsonArray();
		at.add(player.getX());
		at.add(player.getY());
		at.add(player.getZ());
		m.add("p", at);
		JsonArray r = new JsonArray();
		r.add(Mth.positiveModulo(player.getYRot(), 360.0F));
		r.add(player.getXRot());
		m.add("r", r);
		UkLink.get().send(m);
		UKPassthrough.LOG.info(String.format(Locale.ROOT, "Minecraft moved the player from %.1f %.1f %.1f to %.1f %.1f %.1f: V1 follows (move %d)",
			p.fx(), p.fy(), p.fz(), player.getX(), player.getY(), player.getZ(), id));
	}
}
