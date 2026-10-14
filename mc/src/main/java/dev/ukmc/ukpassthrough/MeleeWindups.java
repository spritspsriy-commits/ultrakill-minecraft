package dev.ukmc.ukpassthrough;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * Mobs' melee hits on V1 wind up first, as ULTRAKILL's enemies' do, so V1 can parry them (round 16). Minecraft's mobs hit the
 * moment they decide to (Mob.doHurtTarget); while V1 drives, a hit on the player is held back for {@link #windupTicks}
 * (MobMixin) and ULTRAKILL hears of it ({@link UKPassthrough#v1Windup}): it shows its parry flash on the mob, and a Feedbacker
 * punch on it meanwhile parries it ({@link #parried}). Otherwise the hit lands when the time is up, as Minecraft would have
 * done it (the mob's own attack, knockback and effects included), if the mob still reaches V1 then: a V1 that dashed away
 * isn't hit. Most mobs hit through Mob.doHurtTarget (MobMixin); iron golems, hoglins, zoglins and bees have their own
 * (OwnAttackMobsMixin), slimes and magma cubes hurt by touch (AbstractCubeMob.dealDamage: AbstractCubeMobMixin) and goats in
 * the middle of their ram (RamTarget.tick: RamTargetMixin). Each is held back the same way and lands as itself (round 16b).
 * Server thread.
 */
public final class MeleeWindups {
	/** 0.3 s: about a Filth's bite. */
	public static final int WINDUP_TICKS = 6;
	/** The windup now (the client sets it from the balance settings, round 30; {@link #WINDUP_TICKS} by default). */
	public static volatile int windupTicks = WINDUP_TICKS;
	private static final Map<Integer, Pending> PENDING = new HashMap<>();
	/** A held-back hit lands now: doHurtTarget lets it through. */
	private static boolean landing;
	/** For the dev demo: when the last windup started (server ticks), and its mob. */
	public static volatile int lastWindupTick = -1;
	public static volatile int lastWindupMob = -1;

	/** {@code land}: the mob's own attack again, let through this time. */
	private record Pending(Mob mob, ServerPlayer player, int due, Runnable land) {
	}

	private MeleeWindups() {
	}

	/** From MobMixin and OwnAttackMobsMixin, at the head of a doHurtTarget: true when the hit is held back for now (it doesn't happen yet). */
	public static boolean holdBack(final Mob mob, final ServerLevel level, final Entity target) {
		return target instanceof ServerPlayer player && holdBack(mob, level, player, () -> mob.doHurtTarget(level, player));
	}

	/** At the head of a mob's attack on {@code player}: true when it's held back for now; {@code land} does it again when its time is up. */
	public static boolean holdBack(final Mob mob, final ServerLevel level, final ServerPlayer player, final Runnable land) {
		UKPassthrough.V1Windup windup = UKPassthrough.v1Windup;
		// a dying mob (a slime that still touches V1) doesn't attack at all: Minecraft's own checks say so, and a windup for it
		// was dropped every tick and started again the next
		if (landing || !UKPassthrough.v1Driving || windup == null || !mob.isAlive()) {
			return false;
		}

		// one hit at a time: a mob that tries again before its first has landed is still winding up (a slime touching V1 tries
		// every tick, a ramming goat too)
		if (!PENDING.containsKey(mob.getId())) {
			int now = level.getServer().getTickCount();
			PENDING.put(mob.getId(), new Pending(mob, player, now + windupTicks, land));
			lastWindupTick = now;
			lastWindupMob = mob.getId();
			windup.windup(mob, windupTicks * 50);
		}

		return true;
	}

	/** Every server tick (after the mobs have had theirs): the hits whose time is up land, or miss. */
	public static void tick(final MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}

		int now = server.getTickCount();
		for (Iterator<Pending> i = PENDING.values().iterator(); i.hasNext();) {
			Pending p = i.next();
			if (!p.mob().isAlive() || p.mob().isRemoved() || !p.player().isAlive()) {
				i.remove();
			} else if (now >= p.due()) {
				i.remove();
				land(p);
			}
		}
	}

	private static void land(final Pending p) {
		if (!(p.mob().level() instanceof ServerLevel level) || p.player().level() != level) {
			return;
		}

		// MobMixin measures from V1's newest position (and with V1's shorter reach) while V1 drives
		if (!p.mob().isWithinMeleeAttackRange(p.player())) {
			UKPassthrough.LOG.info("mob {}'s hit missed: V1 was out of its reach when it landed", p.mob().getId());
			return;
		}

		landing = true;
		try {
			p.land().run();
		} finally {
			landing = false;
		}
	}

	/** V1's Feedbacker parried mob {@code id}'s windup (ULTRAKILL): that hit doesn't land. False if it had none (already landed). */
	public static boolean parried(final int id) {
		return PENDING.remove(id) != null;
	}

	/** Mob {@code id} was knocked off its feet mid-windup (the Whiplash, {@link Knocked}): that hit doesn't land. False if it had none. */
	public static boolean interrupted(final int id) {
		return PENDING.remove(id) != null;
	}
}
