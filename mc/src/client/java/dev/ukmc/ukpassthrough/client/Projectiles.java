package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ukmc.ukpassthrough.UKPassthrough;
import dev.ukmc.ukpassthrough.mixin.AbstractArrowAccessor;
import dev.ukmc.ukpassthrough.mixin.ProjectileAccessor;
import dev.ukmc.ukpassthrough.mixin.ShulkerBulletAccessor;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

/**
 * Projectiles flying near the player, for V1's Feedbacker: every kind (round 20: arrows, tridents, fireballs, wind charges,
 * snowballs, eggs, potions, ender pearls, experience bottles, shulker bullets, llama spit, fireworks; not a fishing line), the
 * player's own too. Every tick they go to ULTRAKILL as {"t":"shots","s":[id, x, y, z, vx, vy, vz, width, own, age, ...]}
 * (centre in blocks, velocity in blocks per tick, own 1 for the player's, age in ticks), where each becomes something the
 * punch can parry. A parry comes back as {"t":"parry","id":n,"d":[x,y,z],"boost":bool}: the projectile turns along d (V1's
 * aim), twice as fast, and belongs to the player from then on, so whatever it hits counts as the player's (a ghast's fireball
 * goes back as when Steve hits it; a parried ender pearl takes the player where it lands). A shulker bullet stops steering
 * for its target. The player's own is boosted, as ULTRAKILL's projectile boost: what it hurts goes to ULTRAKILL for blood and
 * "PROJECTILE BOOST" (UKPassthrough.boosted). If an enemy's hit the player in the moment before the parry arrived, the same
 * kind of projectile flies back from the player's eyes instead. The list is made on the client thread, the parry happens on
 * the server thread.
 */
final class Projectiles {
	/** Far enough to see one coming; the punch reaches about 3 blocks. */
	private static final double RANGE = 24.0;
	/** A parried projectile's speed in blocks per tick: twice what it had, within these (a full bow shot is 3). */
	private static final double MIN_SPEED = 2.5;
	private static final double MAX_SPEED = 5.0;
	/** How long (ticks) a projectile's kind is remembered, for the copy that flies back if it's gone. */
	private static final int REMEMBER_TICKS = 40;
	/** How long (ticks) a boosted projectile's hits count as boosted. */
	private static final int BOOSTED_TICKS = 200;

	private static final Map<Integer, Seen> seen = new ConcurrentHashMap<>();
	/** Parried projectiles: not ULTRAKILL's any more (this client still thinks the mob owns them). */
	private static final Set<Integer> parried = ConcurrentHashMap.newKeySet();
	private static boolean sentAny;
	private static int ticks;
	/** When a projectile last hit the player (System.nanoTime), for the dev demo's late punch. */
	static volatile long lastHitNanos;

	private record Seen(EntityType<?> type, int tick) {
	}

	private Projectiles() {
	}

	static void tick(final Minecraft minecraft) {
		ticks++;
		seen.values().removeIf(s -> ticks - s.tick() > REMEMBER_TICKS);
		UkLink link = UkLink.get();
		if (!link.connected() || minecraft.level == null || minecraft.player == null) {
			sentAny = false;
			parried.clear();
			return;
		}

		parried.removeIf(id -> minecraft.level.getEntity(id) == null);
		List<Projectile> shots = minecraft.level.getEntitiesOfClass(Projectile.class, minecraft.player.getBoundingBox().inflate(RANGE),
			p -> parryable(p) && !parried.contains(p.getId()));
		if (shots.isEmpty() && !sentAny) {
			return;
		}

		JsonArray s = new JsonArray();
		for (Projectile p : shots) {
			Vec3 c = p.getBoundingBox().getCenter();
			Vec3 v = p.getDeltaMovement();
			s.add(p.getId());
			s.add(round(c.x));
			s.add(round(c.y));
			s.add(round(c.z));
			s.add(round(v.x));
			s.add(round(v.y));
			s.add(round(v.z));
			s.add(p.getBbWidth());
			s.add(p.getOwner() instanceof Player ? 1 : 0);
			s.add(p.tickCount);
			seen.put(p.getId(), new Seen(p.getType(), ticks));
		}

		JsonObject message = UkLink.type("shots");
		message.add("s", s);
		link.send(message);
		// one empty list after the last one goes, then quiet
		sentAny = !shots.isEmpty();
	}

	/** Every projectile in flight (an arrow or trident stuck in a block isn't), the player's own too; not a fishing line. */
	private static boolean parryable(final Projectile p) {
		if (p.isRemoved() || p instanceof FishingHook) {
			return false;
		}

		return !(p instanceof AbstractArrow arrow) || !((AbstractArrowAccessor) arrow).ukpassthrough$isInGround();
	}

	/** Server thread: a projectile hit the player. Remembered, in case V1 parries it a moment late. */
	static void hitPlayer(final Projectile p) {
		seen.put(p.getId(), new Seen(p.getType(), ticks));
		lastHitNanos = System.nanoTime();
	}

	/** From the link thread. */
	static void onParry(final JsonObject m) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		JsonArray d = m.getAsJsonArray("d");
		Vec3 dir = new Vec3(d.get(0).getAsDouble(), d.get(1).getAsDouble(), d.get(2).getAsDouble());
		if (server == null || dir.lengthSqr() < 1.0E-6) {
			return;
		}

		int id = m.get("id").getAsInt();
		boolean boost = m.has("boost") && m.get("boost").getAsBoolean();
		parried.add(id);
		Seen known = seen.get(id);
		Vec3 aim = dir.normalize();
		server.execute(() -> parry(server, id, aim, known == null ? null : known.type(), boost));
	}

	/** Server thread. */
	private static void parry(final IntegratedServer server, final int id, final Vec3 aim, final EntityType<?> type, final boolean boost) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			return;
		}

		ServerPlayer player = players.getFirst();
		ServerLevel level = player.level();
		Entity entity = level.getEntity(id);
		if (entity instanceof Projectile p && !(p instanceof AbstractArrow a && ((AbstractArrowAccessor) a).ukpassthrough$isInGround())) {
			double speed = Mth.clamp(p.getDeltaMovement().length() * 2.0, MIN_SPEED, MAX_SPEED);
			p.deflect((projectile, by, random, hit) -> {
				projectile.setDeltaMovement(aim.scale(speed));
				projectile.needsSync = true;
			}, player, EntityReference.<Entity>of(player), true, Vec3.ZERO);
			sentBack(p);
			if (boost) {
				int now = server.getTickCount();
				UKPassthrough.boosted.values().removeIf(until -> until < now);
				UKPassthrough.boosted.put(id, now + BOOSTED_TICKS);
			}

			UKPassthrough.LOG.info("{}: {} {} sent {} at {} blocks a tick", boost ? "projectile boost" : "parry", EntityType.getKey(p.getType()), id, boost ? "where V1 aims" : "back",
				String.format(Locale.ROOT, "%.1f", speed));
			return;
		}

		// it hit the player a moment before the parry came (or stuck in a block right next to V1): a copy flies back
		if (entity != null) {
			entity.discard();
		}

		Entity copy = type == null ? null : type.create(level, EntitySpawnReason.TRIGGERED);
		if (!(copy instanceof Projectile p)) {
			UKPassthrough.LOG.info("parry: projectile {} is gone and nothing was sent back", id);
			return;
		}

		Vec3 from = player.getEyePosition().add(aim);
		p.setPos(from.x, from.y - p.getBbHeight() / 2.0, from.z);
		p.setOwner(player);
		p.setDeltaMovement(aim.scale(MIN_SPEED));
		sentBack(p);
		level.addFreshEntity(p);
		UKPassthrough.LOG.info("parry: {} {} was gone, a copy was sent back", EntityType.getKey(type), id);
	}

	private static void sentBack(final Projectile p) {
		((ProjectileAccessor) p).ukpassthrough$setLeftOwner(false);
		if (p instanceof AbstractArrow arrow) {
			arrow.setCritArrow(true);
		} else if (p instanceof ShulkerBullet bullet) {
			// it steers for its target every tick; without one it flies straight on
			((ShulkerBulletAccessor) bullet).ukpassthrough$setFinalTarget(null);
		}
	}

	/**
	 * Server thread (ProjectileMixin): a boosted projectile hit something there: ULTRAKILL's explosion for it ({"t":"boostboom",
	 * "p":[x,y,z],"d":[its direction]}), as its own boosted projectiles explode (round 20b).
	 */
	static void boostedHit(final Projectile p, final Vec3 at) {
		Vec3 d = p.getDeltaMovement().lengthSqr() > 1.0E-6 ? p.getDeltaMovement().normalize() : new Vec3(0.0, -1.0, 0.0);
		JsonObject m = UkLink.type("boostboom");
		JsonArray where = new JsonArray();
		where.add(round(at.x));
		where.add(round(at.y));
		where.add(round(at.z));
		m.add("p", where);
		JsonArray dir = new JsonArray();
		dir.add(round(d.x));
		dir.add(round(d.y));
		dir.add(round(d.z));
		m.add("d", dir);
		UkLink.get().send(m);
		UKPassthrough.LOG.info("projectile boost: {} {} hit at {}: ULTRAKILL's explosion there", EntityType.getKey(p.getType()), p.getId(),
			String.format(Locale.ROOT, "%.1f %.1f %.1f", at.x, at.y, at.z));
	}

	private static double round(final double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}
}
