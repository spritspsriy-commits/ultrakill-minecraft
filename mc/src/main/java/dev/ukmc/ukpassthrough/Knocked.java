package dev.ukmc.ukpassthrough;

import dev.ukmc.ukpassthrough.mixin.EntityAccessor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.HayBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Mobs V1 knocked about, as ULTRAKILL's husks (round 33, a player's report: "Light enemies don't get stunned when being pulled
 * towards the player like in the main game, and trying to splatter an enemy through the knuckle blaster or stomping does not
 * work"):
 * <ul>
 * <li>A mob the Whiplash pulls is stunned (MobMixin: no AI step, so no goals, attacks, path or steering of its own), from the pull
 * until it has landed and {@link #STUN_AFTER} ticks more; a melee hit it was winding up on V1 is dropped. ULTRAKILL's husks are
 * pulled off their feet and can't act until they've landed.</li>
 * <li>A light mob (a box under {@link #HEAVY_VOLUME}, as the Whiplash tells light from heavy) that V1 knocked into the air (a hit's
 * push, a launch, a pull) and lands going down at {@link #SPLAT_SPEED} or faster splatters, as ULTRAKILL's husks do landing at 50
 * units a second: it dies at once, of a fall the player caused (so mobs that never take fall damage don't, and the kill is the
 * player's), and ULTRAKILL shows its splatter and "SPLATTERED" ({@link UKPassthrough#v1Splat}). A Knuckleblaster punch that sends it
 * high, or a ground slam onto it in the air (the slam pushes 1.25 blocks a tick down), does it. Not into water, onto slime, honey,
 * hay, a bed or powder snow (they soften Minecraft's own falls).</li>
 * </ul>
 * A knocked mob is watched while it's in the air; one that never left the ground is let go after {@link #GROUNDED_FOR} ticks.
 * Server thread.
 */
public final class Knocked {
	/** Width x width x height (blocks) from which a mob is heavy: the Whiplash pulls V1 to it (Mobs.HeavyVolume in uk/src), and it doesn't splatter. */
	public static final double HEAVY_VOLUME = 2.0;
	/**
	 * Blocks a tick downwards at landing from which a light mob splatters: a little under ULTRAKILL's 50 units a second (1.25), as
	 * the hits' pushes are capped (MobHits.MAX_PUSH): a mob a Knuckleblaster punch sends straight up comes down at about 1.2.
	 */
	public static final double SPLAT_SPEED = 1.1;
	/** A push smaller than this (blocks a tick) doesn't knock a mob off its feet (a nail's nudge). */
	private static final double LEAST_PUSH = 0.3;
	private static final int STUN_AFTER = 15;
	private static final int GROUNDED_FOR = 10;
	/** Nothing is watched longer than this (ticks). */
	private static final int LONGEST = 300;
	private static final Map<Integer, State> WATCHED = new HashMap<>();
	/** Mobs this far from the player (blocks) are looked at for being in the air (MobStream sends 48 blocks). */
	private static final double AIRBORNE_RANGE = 52.0;
	/**
	 * The mobs near the player in the air off their feet, by id, as the server had them at the end of its last tick (the client thread
	 * reads it: MobStream). The client's own idea of a mob's footing comes with its moves, so one that hasn't moved since it appeared
	 * seemed in the air there.
	 */
	private static volatile Set<Integer> airborne = Set.of();
	/** For the dev demo's before-and-after: false is the old way (no stun, no splatter). */
	public static volatile boolean enabled = true;

	private static final class State {
		final LivingEntity mob;
		/** When it was last knocked (server ticks). */
		int since;
		/** Seen in the air since it was knocked. */
		boolean airborne;
		/** Its speed up (blocks a tick) as it'll move next: the push's, then the end of each tick's in the air. */
		double vy;
		/** Pulled by the Whiplash: stunned until {@link #stunUntil} once it has landed (-1: still in the air). */
		boolean pulled;
		int stunUntil = -1;

		State(final LivingEntity mob, final int since) {
			this.mob = mob;
			this.since = since;
		}
	}

	private Knocked() {
	}

	/** A hit's push or a launch from V1 has just been added to the mob's motion. */
	public static void pushed(final LivingEntity mob, final Vec3 push, final int now) {
		if (!enabled || push.length() < LEAST_PUSH) {
			return;
		}

		State s = WATCHED.computeIfAbsent(mob.getId(), id -> new State(mob, now));
		s.since = now;
		s.vy = mob.getDeltaMovement().y;
	}

	/** The Whiplash pulls the mob this tick (MobHits.pull, after setting its motion). */
	public static void pulled(final LivingEntity mob, final int now) {
		if (!enabled) {
			return;
		}

		State s = WATCHED.computeIfAbsent(mob.getId(), id -> new State(mob, now));
		s.since = now;
		s.vy = mob.getDeltaMovement().y;
		s.stunUntil = -1;
		if (!s.pulled) {
			s.pulled = true;
			if (mob instanceof Mob m) {
				stun(m);
			}
		}
	}

	/** MobMixin: whether the mob's AI waits this tick. */
	public static boolean stunned(final Mob mob) {
		State s = WATCHED.get(mob.getId());
		return s != null && s.pulled && s.mob == mob;
	}

	/** The mob stops what it was doing: its path, its own steering, a hit winding up on V1, a creeper's fuse. */
	private static void stun(final Mob mob) {
		mob.getNavigation().stop();
		mob.setZza(0.0F);
		mob.setXxa(0.0F);
		mob.setSpeed(0.0F);
		mob.setJumping(false);
		if (mob instanceof Creeper creeper) {
			creeper.setSwellDir(-1);
		}

		if (MeleeWindups.interrupted(mob.getId())) {
			UKPassthrough.LOG.info("mob {} pulled by the Whiplash: its hit on V1 won't land", mob.getId());
		}
	}

	/** Whether mob {@code id} was in the air off its feet at the server's last tick (any thread). */
	public static boolean inAir(final int id) {
		return airborne.contains(id);
	}

	/** Every server tick, after the mobs have moved. */
	public static void tick(final MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			airborne = Set.of();
		} else {
			ServerPlayer player = players.getFirst();
			Set<Integer> now = new HashSet<>();
			for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(AIRBORNE_RANGE), e -> !(e instanceof Player))) {
				if (!e.onGround() && !e.isInWater() && !e.isInLava() && !e.isPassenger() && !e.onClimbable() && !flies(e) && !e.isDeadOrDying()) {
					now.add(e.getId());
				}
			}

			airborne = now;
		}

		if (WATCHED.isEmpty()) {
			return;
		}

		int now = server.getTickCount();
		for (Iterator<State> i = WATCHED.values().iterator(); i.hasNext();) {
			State s = i.next();
			LivingEntity mob = s.mob;
			if (!mob.isAlive() || mob.isRemoved() || now - s.since > LONGEST || !(mob.level() instanceof ServerLevel level)) {
				i.remove();
				continue;
			}

			if (mob.isInWater() || mob.isInLava() || mob.isPassenger() || mob.onClimbable()) {
				// caught by the water, a ladder or a ride: no splatter, the stun ends as on landing
				s.airborne = false;
				if (!s.pulled || landed(s, now)) {
					i.remove();
				}

				continue;
			}

			if (!mob.onGround()) {
				s.airborne = true;
				s.vy = mob.getDeltaMovement().y;
				continue;
			}

			boolean justLanded = s.airborne;
			s.airborne = false;
			if (justLanded && -s.vy >= SPLAT_SPEED && splatters(level, mob)) {
				splat(server, level, mob, -s.vy);
				i.remove();
			} else if (s.pulled ? landed(s, now) : justLanded || now - s.since > GROUNDED_FOR) {
				i.remove();
			}
		}
	}

	/** A pulled mob is on its feet again: its stun runs out {@link #STUN_AFTER} ticks later. True once it has. */
	private static boolean landed(final State s, final int now) {
		if (s.stunUntil < 0) {
			s.stunUntil = now + STUN_AFTER;
		}

		return now >= s.stunUntil;
	}

	/** Light, falling as Minecraft has it, and onto ground that doesn't soften a fall. */
	private static boolean splatters(final ServerLevel level, final LivingEntity mob) {
		double volume = mob.getBbWidth() * mob.getBbWidth() * mob.getBbHeight();
		if (volume >= HEAVY_VOLUME || mob.isNoGravity() || flies(mob)) {
			return false;
		}

		BlockState below = level.getBlockState(mob.getOnPos());
		return !(below.getBlock() instanceof SlimeBlock || below.getBlock() instanceof HoneyBlock || below.getBlock() instanceof HayBlock
			|| below.getBlock() instanceof BedBlock || below.getBlock() instanceof PowderSnowBlock);
	}

	/** Mobs that fly (or hover) on their own: never knocked off their feet, and not "in the air" for ULTRAKILL's rockets either. */
	public static boolean flies(final LivingEntity mob) {
		return mob.isNoGravity() || mob instanceof Ghast || mob instanceof HappyGhast || mob instanceof Phantom || mob instanceof Vex || mob instanceof Bat
			|| mob instanceof Bee || mob instanceof Parrot || mob instanceof Allay || mob instanceof Blaze;
	}

	private static void splat(final MinecraftServer server, final ServerLevel level, final LivingEntity mob, final double speed) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		ServerPlayer player = players.isEmpty() ? null : players.getFirst();
		DamageSource fall = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.FALL), player);
		((EntityAccessor) mob).ukpassthrough$setInvulnerableTime(0);
		mob.hurtServer(level, fall, 1.0E6F);
		boolean dead = mob.isDeadOrDying();
		UKPassthrough.LOG.info("mob {} ({}) landed at {} blocks a tick after V1 knocked it about: {}", mob.getId(), mob.getType().toShortString(),
			String.format(Locale.ROOT, "%.2f", speed), dead ? "splattered" : "it didn't die (it takes no falls)");
		UKPassthrough.V1Splat splatted = UKPassthrough.v1Splat;
		if (dead && splatted != null) {
			splatted.splat(mob);
		}
	}
}
