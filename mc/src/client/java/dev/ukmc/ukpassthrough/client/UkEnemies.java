package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ukmc.ukpassthrough.UKPassthrough;
import dev.ukmc.ukpassthrough.UkEnemy;
import dev.ukmc.ukpassthrough.mixin.MobAccessor;
import dev.ukmc.ukpassthrough.mixin.NearestAttackableTargetGoalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * ULTRAKILL's own enemies (spawned with V1's Sandbox arm) and Minecraft's mobs fight each other (round 18, both ways: the user's
 * choice). ULTRAKILL sends its living enemies 20 times a second ({"t":"ukenemies","e":[id, x, y, z, width, height, ...]}, feet in
 * blocks, ids its own) and each one has a {@link UkEnemy} here at that spot and size, which Minecraft's mobs can see, target and
 * hit:
 * <ul>
 * <li>hostile mobs that hunt players get a goal for ULTRAKILL's enemies too, at the same priority ({@link HuntGoal}): one goes for
 * whichever is nearer, V1 or an enemy it can see, and switches when the other is {@link #SWITCH_MARGIN} blocks nearer (unless
 * what it's after has just hurt it: then it fights back); neutral mobs such as endermen and piglins don't seek them out;</li>
 * <li>iron and snow golems go for them as for any monster;</li>
 * <li>a mob a ULTRAKILL enemy hurt fights back at it (the hit is that enemy's mob attack: MobHits), or flees if it's passive;</li>
 * <li>what hurts one goes to ULTRAKILL ({"t":"ukhurt","id":its id,"dmg":Minecraft damage,"by":the attacker,"explosion":bool,
 * "projectile":bool,"f":[push away from what hit it, blocks]}) as damage on that enemy. V1 fights them with ULTRAKILL's own
 * weapons there: the player's own hits here (a sword through the crosshair can't reach them anyway) don't count.</li>
 * </ul>
 * Server thread unless said otherwise.
 */
final class UkEnemies {
	/** The enemies' entities by ULTRAKILL's id. */
	private static final Map<Integer, UkEnemy> BY_UK_ID = new HashMap<>();
	/** Hostile mobs with a {@link HuntGoal}. */
	private static final Set<Mob> HUNTERS = Collections.newSetFromMap(new WeakHashMap<>());
	/** A hunting mob changes between V1 and an enemy when the other is this much nearer (blocks), not back and forth. */
	private static final double SWITCH_MARGIN = 3.0;
	/** A mob hurt by what it's after keeps at it this long (ticks), as Minecraft's fighting back does. */
	private static final int RETALIATE_TICKS = 100;

	private UkEnemies() {
	}

	/** At load: the hook for hurts, hostile mobs' goal as they load. */
	static void init() {
		UKPassthrough.ukEnemyHurt = UkEnemies::hurt;
		ServerTickEvents.END_SERVER_TICK.register(UkEnemies::retarget);
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Mob mob) {
				HuntGoal.add(mob);
			}
		});
	}

	/** From the link thread: ULTRAKILL's living enemies now. */
	static void onList(final JsonObject m) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		JsonArray e = m.getAsJsonArray("e");
		if (server == null || e == null) {
			return;
		}

		List<double[]> list = new ArrayList<>();
		for (int i = 0; i + 5 < e.size(); i += 6) {
			list.add(new double[] {e.get(i).getAsDouble(), e.get(i + 1).getAsDouble(), e.get(i + 2).getAsDouble(), e.get(i + 3).getAsDouble(), e.get(i + 4).getAsDouble(),
				e.get(i + 5).getAsDouble()});
		}

		server.execute(() -> follow(server, list));
	}

	private static void follow(final IntegratedServer server, final List<double[]> list) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		ServerLevel level = players.isEmpty() ? null : players.getFirst().level();
		Map<Integer, double[]> now = new HashMap<>();
		for (double[] d : list) {
			now.put((int) d[0], d);
		}

		for (Iterator<Map.Entry<Integer, UkEnemy>> i = BY_UK_ID.entrySet().iterator(); i.hasNext();) {
			Map.Entry<Integer, UkEnemy> entry = i.next();
			UkEnemy enemy = entry.getValue();
			if (!now.containsKey(entry.getKey()) || enemy.isRemoved() || enemy.level() != level) {
				enemy.discard();
				i.remove();
			}
		}

		if (level == null) {
			return;
		}

		for (double[] d : now.values()) {
			int id = (int) d[0];
			UkEnemy enemy = BY_UK_ID.get(id);
			if (enemy == null) {
				enemy = UkEnemy.type().create(level, EntitySpawnReason.TRIGGERED);
				if (enemy == null) {
					continue;
				}

				enemy.follow(id, d[1], d[2], d[3], (float) d[4], (float) d[5]);
				level.addFreshEntity(enemy);
				BY_UK_ID.put(id, enemy);
				UKPassthrough.LOG.info("ULTRAKILL enemy {} is in Minecraft as entity {}, {} by {} blocks at {}", id, enemy.getId(), String.format(Locale.ROOT, "%.1f", d[4]),
					String.format(Locale.ROOT, "%.1f", d[5]), String.format(Locale.ROOT, "%.1f %.1f %.1f", d[1], d[2], d[3]));
			} else {
				enemy.follow(id, d[1], d[2], d[3], (float) d[4], (float) d[5]);
			}
		}
	}

	/** ULTRAKILL enemy {@code ukId}'s entity here, or null. */
	static UkEnemy byUkId(final int ukId) {
		UkEnemy enemy = BY_UK_ID.get(ukId);
		return enemy == null || enemy.isRemoved() ? null : enemy;
	}

	/** The nearest of ULTRAKILL's enemies {@code mob} sees within {@code range}, if it's nearer than any player it could go for. */
	static UkEnemy nearestFor(final Mob mob, final double range) {
		UkEnemy best = nearestSeen(mob, range);
		// whichever is nearer: V1 nearer and in sight, and the mob's own goal for players has it
		Player player = best == null ? null : nearestPlayerSeen(mob, range);
		return player != null && mob.distanceToSqr(player) < mob.distanceToSqr(best) ? null : best;
	}

	private static UkEnemy nearestSeen(final Mob mob, final double range) {
		UkEnemy best = null;
		double bestDistance = range * range;
		for (UkEnemy enemy : BY_UK_ID.values()) {
			double d = mob.distanceToSqr(enemy);
			if (d < bestDistance && enemy.isAlive() && enemy.level() == mob.level() && mob.getSensing().hasLineOfSight(enemy)) {
				best = enemy;
				bestDistance = d;
			}
		}

		return best;
	}

	private static Player nearestPlayerSeen(final Mob mob, final double range) {
		Player best = null;
		double bestDistance = range * range;
		for (Player player : mob.level().players()) {
			double d = mob.distanceToSqr(player);
			if (d < bestDistance && player.isAlive() && !player.isSpectator() && !player.isCreative() && mob.getSensing().hasLineOfSight(player)) {
				best = player;
				bestDistance = d;
			}
		}

		return best;
	}

	/**
	 * Every half second: a hunting mob after V1 goes for one of ULTRAKILL's enemies that's come {@link #SWITCH_MARGIN} nearer, and
	 * the other way round. Its running target goal carries on with the new target (Minecraft's target goals keep whatever the mob's
	 * target is while it's valid), so this doesn't fight Minecraft's own choice of goals.
	 */
	private static void retarget(final MinecraftServer server) {
		if (BY_UK_ID.isEmpty() || server.getTickCount() % 10 != 0) {
			return;
		}

		for (Mob mob : new ArrayList<>(HUNTERS)) {
			LivingEntity target = mob.getTarget();
			if (!mob.isAlive() || target == null || !target.isAlive()
				|| (target == mob.getLastHurtByMob() && mob.tickCount - mob.getLastHurtByMobTimestamp() < RETALIATE_TICKS)) {
				continue;
			}

			double range = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
			LivingEntity other = target instanceof Player ? nearestSeen(mob, range) : target instanceof UkEnemy ? nearestPlayerSeen(mob, range) : null;
			if (other != null && mob.distanceTo(other) + SWITCH_MARGIN < mob.distanceTo(target)) {
				mob.setTarget(other);
				UKPassthrough.LOG.info("mob {} ({}) turns to {}: nearer than {}", mob.getId(), mob.getType().toShortString(), describe(other), describe(target));
			}
		}
	}

	private static String describe(final LivingEntity e) {
		return e instanceof UkEnemy enemy ? "ULTRAKILL enemy " + enemy.ukId() : e instanceof Player ? "V1" : e.getType().toShortString() + " " + e.getId();
	}

	/** UkEnemy.hurtServer: on to ULTRAKILL. */
	private static void hurt(final UkEnemy enemy, final DamageSource source, final float amount) {
		Entity by = source.getEntity();
		if (by instanceof Player) {
			return;
		}

		// away from what hit it, as Minecraft's knockback
		Entity from = source.getDirectEntity() != null ? source.getDirectEntity() : by;
		Vec3 away = enemy.position().subtract(from.position()).multiply(1.0, 0.0, 1.0);
		away = away.lengthSqr() < 1.0E-6 ? Vec3.ZERO : away.normalize().scale(0.4).add(0.0, 0.2, 0.0);
		boolean explosion = source.is(DamageTypeTags.IS_EXPLOSION);
		boolean projectile = source.is(DamageTypeTags.IS_PROJECTILE);
		JsonObject m = UkLink.type("ukhurt");
		m.addProperty("id", enemy.ukId());
		// times the balance settings' mobs on ULTRAKILL's enemies (round 30)
		m.addProperty("dmg", amount * Math.max(0.0, ModSettings.get().mobsOnEnemies));
		m.addProperty("by", by.getId());
		m.addProperty("explosion", explosion);
		m.addProperty("projectile", projectile);
		JsonArray f = new JsonArray();
		f.add(away.x);
		f.add(away.y);
		f.add(away.z);
		m.add("f", f);
		UkLink.get().send(m);
		UKPassthrough.LOG.info("{} {} hurt ULTRAKILL enemy {} for {}{}: goes to ULTRAKILL", by.getType().toShortString(), by.getId(), enemy.ukId(), String.format(Locale.ROOT, "%.1f", amount),
			explosion ? " (explosion)" : projectile ? " (projectile)" : "");
	}

	/**
	 * A hostile mob's goal for ULTRAKILL's enemies, beside its goal for players and at its priority (so neither takes over from the
	 * other once it has a target, as two of Minecraft's own wouldn't): it picks the nearest it sees, if nearer than V1.
	 */
	static final class HuntGoal extends TargetGoal {
		private UkEnemy found;

		private HuntGoal(final Mob mob) {
			super(mob, true);
			this.setFlags(java.util.EnumSet.of(Flag.TARGET));
		}

		/** For a hostile mob that hunts players (Minecraft's own goal for them says at what priority), as it loads. */
		static void add(final Mob mob) {
			if (!(mob instanceof Enemy) || mob instanceof NeutralMob || mob instanceof UkEnemy) {
				return;
			}

			int priority = -1;
			for (WrappedGoal g : ((MobAccessor) mob).ukpassthrough$targetSelector().getAvailableGoals()) {
				if (g.getGoal() instanceof HuntGoal) {
					return;
				}

				if (g.getGoal() instanceof NearestAttackableTargetGoal<?> goal && Player.class.isAssignableFrom(((NearestAttackableTargetGoalAccessor) goal).ukpassthrough$targetType())) {
					priority = priority < 0 ? g.getPriority() : Math.min(priority, g.getPriority());
				}
			}

			if (priority >= 0) {
				((MobAccessor) mob).ukpassthrough$targetSelector().addGoal(priority, new HuntGoal(mob));
				HUNTERS.add(mob);
			}
		}

		@Override
		public boolean canUse() {
			// as often as Minecraft's own looks for a player (NearestAttackableTargetGoal: 1 in 10 ticks on average)
			if (BY_UK_ID.isEmpty() || this.mob.getRandom().nextInt(reducedTickDelay(10)) != 0) {
				return false;
			}

			this.found = nearestFor(this.mob, this.getFollowDistance());
			return this.found != null;
		}

		@Override
		public void start() {
			this.mob.setTarget(this.found);
			UKPassthrough.LOG.info("mob {} ({}) goes for ULTRAKILL enemy {}", this.mob.getId(), this.mob.getType().toShortString(), this.found.ukId());
			super.start();
		}

		@Override
		public boolean canContinueToUse() {
			LivingEntity target = this.mob.getTarget();
			return target instanceof UkEnemy && super.canContinueToUse();
		}
	}
}
