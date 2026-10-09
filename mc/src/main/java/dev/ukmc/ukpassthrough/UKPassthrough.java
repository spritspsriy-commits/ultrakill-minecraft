package dev.ukmc.ukpassthrough;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UKPassthrough implements ModInitializer {
	public static final String ID = "ukpassthrough";
	public static final String VERSION = "0.1.0";
	public static final Logger LOG = LoggerFactory.getLogger(ID);
	/**
	 * True while V1 moves the player (the client sets it every tick). The integrated server runs in this JVM, so
	 * its movement checks and the player's physics read it too.
	 */
	public static volatile boolean v1Driving;
	/**
	 * V1 lies dead in ULTRAKILL (round 29; from its death until it comes back): Steve, still standing where it fell, picks nothing up
	 * (PlayerMixin), so the items it dropped stay on the ground as a dead player's do.
	 */
	public static volatile boolean v1Dead;
	/** Where the player's damage goes while V1 drives (set by the client, called on the server thread). */
	public static volatile V1Hurt v1Hurt;
	/** Where the player's healing goes while V1 drives (set by the client, called on the server thread). */
	public static volatile V1Heal v1Heal;
	/** Where knockback on the player goes while V1 drives (set by the client, called on the server thread). */
	public static volatile V1Push v1Push;
	/** True while the poison effect hurts the player (PoisonMobEffectMixin): poison doesn't kill, Steve or V1. Server thread. */
	public static boolean poisonTick;
	/**
	 * The player while V1's punch uses or breaks a block (server thread). Minecraft leaves whoever does something out of its
	 * sounds and particles (that player's own client plays them), but V1's punches don't come through the client: the
	 * player gets them too (ServerLevelMixin).
	 */
	public static Entity punchingPlayer;
	/**
	 * Where V1's feet are now, as ULTRAKILL last said (null while it isn't sending): fresher than the player's own position on
	 * the server, which follows a tick or two behind (set by the client; MobReach).
	 */
	public static volatile java.util.function.Supplier<Vec3> v1Feet;
	/** Where a mob's melee hit on V1 that winds up is announced (set by the client, called on the server thread; MeleeWindups). */
	public static volatile V1Windup v1Windup;
	/** Where hits from the player's own weapon go while V1 drives (set by the client, called on the server thread). */
	public static volatile V1Strike v1Strike;
	/**
	 * Projectiles of the player's that V1 boosted (round 20: the Feedbacker on V1's own arrow, ULTRAKILL's projectile boost), by
	 * id, and the server tick until which a hit from one counts as boosted; what they hurt goes to {@link #v1Boost}
	 * (LivingEntityMixin). Server thread.
	 */
	public static final java.util.Map<Integer, Integer> boosted = new java.util.HashMap<>();
	/** Where hits from boosted projectiles go (set by the client, called on the server thread). */
	public static volatile V1Boost v1Boost;
	/** Where a boosted projectile's hit goes, for ULTRAKILL's explosion there (set by the client, called on the server thread). */
	public static volatile BoostedHit boostedHit;
	/**
	 * The player while its own attack runs on the server and V1 drives (a swing, the sweep beside it, the mace's smash, a
	 * spear's stab: PlayerMixin), else null; the mobs it hurts meanwhile go to {@link #v1Strike} (LivingEntityMixin).
	 * ULTRAKILL's own hits on mobs (MobHits) are the player's attacks too, but don't run through it. Server thread.
	 */
	public static Player striking;
	/** Where hurts on ULTRAKILL's enemies in Minecraft ({@link UkEnemy}) go (set by the client, called on the server thread). */
	public static volatile UkEnemyHurt ukEnemyHurt;
	/** True while an experience orb gives the player its points (ExperienceOrbMixin). Server thread. */
	public static boolean orbTouch;
	/** Where an experience orb's points go instead of the player's experience (set by the client, called on the server thread). */
	public static volatile XpPickup xpPickup;

	/** Experience the player picks up from an orb (after Mending had its share), for ULTRAKILL's P in Progression Mode. */
	public interface XpPickup {
		/** @return true if they were taken (the player doesn't get them as experience) */
		boolean take(int points);
	}

	/** Damage the player would have taken, for V1 instead. */
	public interface V1Hurt {
		/**
		 * @param amount Minecraft health points, after difficulty, blocking, armor, Resistance and absorption hearts
		 * @param type the damage type, e.g. "minecraft:mob_attack"
		 * @param source the entity that hit (an arrow, a fireball, a zombie), or null
		 * @param nonlethal it mustn't kill (poison)
		 */
		void hurt(float amount, String type, boolean explosion, Entity source, boolean nonlethal);
	}

	/** A push the player would have had (a mob's knockback, an arrow's), for V1 instead. */
	public interface V1Push {
		/** In blocks a tick, as Minecraft adds it to the player's motion. */
		void push(double x, double y, double z, String why);
	}

	/** A mob the player's own weapon (in V1's hand) hurt, for ULTRAKILL's blood and style points. */
	public interface V1Strike {
		/**
		 * @param damage the health (absorption hearts included) it lost
		 * @param dead it's dying from it
		 * @param weapon what the player held
		 */
		void struck(LivingEntity target, float damage, boolean dead, ItemStack weapon);
	}

	/** A mob a boosted projectile hurt, for ULTRAKILL's blood and "PROJECTILE BOOST". */
	public interface V1Boost {
		/** @param damage the health (absorption hearts included) it lost */
		void boosted(LivingEntity target, float damage, boolean dead);
	}

	/** A projectile V1 boosted hit something (ProjectileMixin). */
	public interface BoostedHit {
		/** @param at where it hit (blocks) */
		void hit(net.minecraft.world.entity.projectile.Projectile projectile, net.minecraft.world.phys.Vec3 at);
	}

	/** A mob's melee hit on V1 is winding up (MeleeWindups): ULTRAKILL shows it can be parried. */
	public interface V1Windup {
		/** @param ms how long until it lands */
		void windup(Mob mob, int ms);
	}

	/** Something hurt one of ULTRAKILL's enemies in Minecraft's world ({@link UkEnemy}): for ULTRAKILL, as damage on that enemy. */
	public interface UkEnemyHurt {
		/** @param amount Minecraft health points, after the hurt cooldown */
		void hurt(UkEnemy enemy, DamageSource source, float amount);
	}

	/** Healing the player would have had (instant health, regeneration), for V1 instead. */
	public interface V1Heal {
		/** @param amount Minecraft health points */
		void heal(float amount);
	}

	@Override
	public void onInitialize() {
		LOG.info("ULTRAKILL passthrough {} loaded", VERSION);
		ServerTickEvents.END_SERVER_TICK.register(MeleeWindups::tick);
		UkEnemy.register();
		PlacedBlocks.register();
	}
}
