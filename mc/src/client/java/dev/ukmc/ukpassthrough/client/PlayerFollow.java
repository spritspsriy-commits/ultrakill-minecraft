package dev.ukmc.ukpassthrough.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps Steve's body on V1's feet while V1 drives, so mobs chase the right spot, chunks load around V1 and the
 * server knows where the player is. Steve always reports standing on the ground: V1's jumps and slams must not
 * add up to fall damage (ULTRAKILL has none).
 */
public final class PlayerFollow {
	/** Further than this (a respawn, a long dash between ticks) the body jumps there instead of sliding. */
	private static final double SNAP_SQ = 16.0 * 16.0;

	private PlayerFollow() {
	}

	/** At the start of the player's tick and again right before its position goes to the server. */
	public static void apply(final LocalPlayer player) {
		if (!Control.driving()) {
			return;
		}

		V1State.Pose p = V1State.live();
		if (p == null) {
			return;
		}

		boolean snap = player.distanceToSqr(p.fx(), p.fy(), p.fz()) > SNAP_SQ;
		player.setPos(p.fx(), p.fy(), p.fz());
		if (snap) {
			player.xo = player.xOld = p.fx();
			player.yo = player.yOld = p.fy();
			player.zo = player.zOld = p.fz();
		}

		player.setDeltaMovement(Vec3.ZERO);
		// Minecraft's yaw goes on past 360 as the player turns; V1's (Unity's) starts again at 0. Kept going on, or whatever
		// eases after the yaw (the first-person item's sway, the turn between two ticks) swings round the long way each time
		// V1 faces south.
		float yaw = player.getYRot() + Mth.wrapDegrees(p.yaw() - player.getYRot());
		player.setYRot(yaw);
		player.setXRot(p.pitch());
		player.yHeadRot = yaw;
		player.yBodyRot = yaw;
		player.setOnGround(true);
		player.resetFallDistance();
	}
}
