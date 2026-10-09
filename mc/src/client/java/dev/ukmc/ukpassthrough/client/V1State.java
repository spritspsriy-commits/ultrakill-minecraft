package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** V1's camera and body as ULTRAKILL last reported them, in Minecraft coordinates. Written by the link thread. */
public final class V1State {
	/** A camera older than this means ULTRAKILL stopped: Minecraft's own camera takes over again. */
	private static final long LIVE_NANOS = 500_000_000L;

	/**
	 * @param x V1's eyes (its first-person camera); yaw/pitch/roll in Minecraft degrees (pitch positive = down); fov vertical degrees
	 * @param fx V1's feet; vx its velocity in blocks per second
	 * @param cx where the picture is drawn from: the eyes in first person, behind or in front of V1 in third person
	 * @param check development: which way ULTRAKILL's camera really points (forward, then up; Minecraft's axes), or null
	 */
	public record Pose(
		double x, double y, double z, float yaw, float pitch, float roll, float fov,
		double fx, double fy, double fz, double vx, double vy, double vz,
		double cx, double cy, double cz, float cyaw, float cpitch, float croll, long receivedNanos, float[] check
	) {
	}

	private static volatile Pose latest;
	/**
	 * The level the latest pose belongs to. A dimension change gives Minecraft a new level at once, before the rest of
	 * the tick moves the player (PlayerFollow) and before the new world is announced: the old pose must not count then.
	 */
	private static volatile ClientLevel latestLevel;
	/**
	 * Development (round 22's camera check): the most Minecraft's camera was off ULTRAKILL's since the last {@link #takeCameraError},
	 * in degrees (its forward, its up), over how many frames; set by CameraMixin.
	 */
	private static float forwardError;
	private static float upError;
	private static int checkedFrames;

	private V1State() {
	}

	/**
	 * {"t":"cam","w":world,"m":move,"p":[x,y,z],"r":[yaw,pitch,roll],"fov":deg,"pl":[feet],"v":[velocity]}, and in third person
	 * "c":[x,y,z,yaw,pitch,roll], the camera the picture was drawn from. One from before
	 * the last world or dimension change, or before ULTRAKILL heard of the last time Minecraft moved the player
	 * ({@link Teleports}), is ignored: it was still on its way and would pull the player back.
	 */
	static void updateCamera(final JsonObject m) {
		if (m.has("w") && m.get("w").getAsInt() != BlockStream.worldId()) {
			return;
		}

		if ((m.has("m") ? m.get("m").getAsInt() : 0) != Teleports.id()) {
			return;
		}

		latestLevel = BlockStream.announcedLevel();

		JsonArray p = m.getAsJsonArray("p");
		JsonArray r = m.getAsJsonArray("r");
		JsonArray pl = m.getAsJsonArray("pl");
		JsonArray v = m.getAsJsonArray("v");
		JsonArray c = m.has("c") ? m.getAsJsonArray("c") : null;
		float[] check = null;
		if (m.has("cv")) {
			JsonArray cv = m.getAsJsonArray("cv");
			check = new float[6];
			for (int i = 0; i < 6; i++) {
				check[i] = cv.get(i).getAsFloat();
			}
		}

		double x = p.get(0).getAsDouble(), y = p.get(1).getAsDouble(), z = p.get(2).getAsDouble();
		float yaw = r.get(0).getAsFloat(), pitch = r.get(1).getAsFloat(), roll = r.get(2).getAsFloat();
		latest = new Pose(
			x, y, z, yaw, pitch, roll, m.get("fov").getAsFloat(),
			pl.get(0).getAsDouble(), pl.get(1).getAsDouble(), pl.get(2).getAsDouble(),
			v.get(0).getAsDouble(), v.get(1).getAsDouble(), v.get(2).getAsDouble(),
			c != null ? c.get(0).getAsDouble() : x, c != null ? c.get(1).getAsDouble() : y, c != null ? c.get(2).getAsDouble() : z,
			c != null ? c.get(3).getAsFloat() : yaw, c != null ? c.get(4).getAsFloat() : pitch, c != null ? c.get(5).getAsFloat() : roll,
			System.nanoTime(), check
		);
	}

	/** From CameraMixin, each frame the camera message carried ULTRAKILL's own camera: how far Minecraft's is off it (degrees). */
	public static void noteCameraError(final float forward, final float up) {
		forwardError = Math.max(forwardError, forward);
		upError = Math.max(upError, up);
		checkedFrames++;
	}

	/** {most forward error, most up error, frames}, and starts again. */
	static float[] takeCameraError() {
		float[] e = {forwardError, upError, checkedFrames};
		forwardError = 0.0F;
		upError = 0.0F;
		checkedFrames = 0;
		return e;
	}

	/** A world or dimension change: the last pose was somewhere else. */
	static void clear() {
		latest = null;
	}

	/** The latest pose while ULTRAKILL keeps sending, else null. */
	public static Pose live() {
		Pose p = latest;
		return p != null && System.nanoTime() - p.receivedNanos() < LIVE_NANOS && latestLevel == Minecraft.getInstance().level ? p : null;
	}

	public static boolean fresh() {
		return live() != null;
	}

	public static double x() {
		return latest.fx();
	}

	public static double y() {
		return latest.fy();
	}

	public static double z() {
		return latest.fz();
	}
}
