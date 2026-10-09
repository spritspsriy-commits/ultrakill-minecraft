package dev.ukmc.ukpassthrough;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How far a mob's melee reaches V1 (round 16; MobMixin). Measured in round 16, mobs reached V1 exactly as far as Steve (0.83
 * past their box at most); they only look further off at ULTRAKILL's wide field of view (125 degrees up and down on this PC,
 * Minecraft's is 70), and the user chose a shorter reach for V1: {@link #V1_SHARE} of it. The server only knows the player
 * where the client last put it, a tick or two behind V1 (Steve follows V1, which moves about twice as fast), so the reach is
 * measured from V1's feet as ULTRAKILL last said ({@link UKPassthrough#v1Feet}). Server thread.
 */
public final class MobReach {
	/** Mob.DEFAULT_ATTACK_REACH: how far past its own box a mob's melee reaches, across (its box is widened that much). */
	public static final double REACH = Math.sqrt(2.04) - 0.6;
	/** The share of a mob's reach it has against V1 (the user's choice): 0.41 blocks past its box instead of 0.83. */
	public static final double V1_SHARE = 0.5;
	/** The share now (the client sets it from the balance settings, round 30; {@link #V1_SHARE} by default). */
	public static volatile double v1Share = V1_SHARE;

	private MobReach() {
	}

	/** The player's box at V1's feet now, or null if ULTRAKILL hasn't said where they are. */
	public static AABB v1Box(final Player player) {
		java.util.function.Supplier<Vec3> feet = UKPassthrough.v1Feet;
		Vec3 at = feet == null ? null : feet.get();
		return at == null ? null : player.getBoundingBox().move(at.subtract(player.position()));
	}

	/** How far {@code target} is outside {@code mob}'s box across (the larger of the gaps along x and z; 0 if they overlap). */
	public static double gap(final Mob mob, final AABB target) {
		AABB box = mob.getBoundingBox();
		double x = Math.max(0.0, Math.max(box.minX - target.maxX, target.minX - box.maxX));
		double z = Math.max(0.0, Math.max(box.minZ - target.maxZ, target.minZ - box.maxZ));
		return Math.max(x, z);
	}
}
