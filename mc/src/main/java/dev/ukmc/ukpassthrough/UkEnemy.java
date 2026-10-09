package dev.ukmc.ukpassthrough;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * One of ULTRAKILL's own enemies (spawned with V1's Sandbox arm) in Minecraft's world, so Minecraft's mobs can fight it (round
 * 18): the mirror of the stand-ins ULTRAKILL has for Minecraft's mobs. It's nothing to look at (no renderer draws it: ULTRAKILL's
 * picture shows the enemy) and nothing to bump into, and has the enemy's place and size, which ULTRAKILL sends 20 times a second
 * (the client's UkEnemies puts them here). As a monster ({@link Enemy}), iron and snow golems go for it on their own; hostile mobs
 * get a goal for it (UkEnemies.HuntGoal). What hurts it doesn't take its own health: each hurt goes to ULTRAKILL as damage on the
 * enemy ({@link UKPassthrough#ukEnemyHurt}), and a mob it hurt fights back at it, as Minecraft's mobs do. It isn't saved with the
 * world, and goes when ULTRAKILL stops listing its enemy.
 */
public final class UkEnemy extends PathfinderMob implements Enemy {
	public static final ResourceKey<EntityType<?>> KEY = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(UKPassthrough.ID, "ultrakill_enemy"));
	private static final EntityDataAccessor<Float> WIDTH = SynchedEntityData.defineId(UkEnemy.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(UkEnemy.class, EntityDataSerializers.FLOAT);
	/** Minecraft's own: a second hurt within this many ticks only counts for what it does over the first. */
	private static final int HURT_COOLDOWN = 10;
	private static EntityType<UkEnemy> type;

	/** The enemy's EnemyIdentifier's instance id in ULTRAKILL (server side; 0 until set). */
	private int ukId;
	/** When ULTRAKILL last listed it (server ticks of this entity). */
	private int lastSeen;
	private int lastHurtTick = -HURT_COOLDOWN;
	private float lastHurtAmount;

	public UkEnemy(final EntityType<? extends UkEnemy> type, final Level level) {
		super(type, level);
		this.setNoGravity(true);
		this.setSilent(true);
		this.setPersistenceRequired();
	}

	/** At load (UKPassthrough.onInitialize): the entity type, its attributes. */
	static void register() {
		type = Registry.register(BuiltInRegistries.ENTITY_TYPE, KEY, EntityType.Builder.of(UkEnemy::new, MobCategory.MISC)
			.sized(0.6F, 1.8F).noSave().noSummon().fireImmune().clientTrackingRange(10).updateInterval(1).build(KEY));
		FabricDefaultAttributeRegistry.register(type, PathfinderMob.createMobAttributes());
	}

	public static EntityType<UkEnemy> type() {
		return type;
	}

	public int ukId() {
		return this.ukId;
	}

	/** Server thread: ULTRAKILL listed it again, here and this big (blocks). */
	public void follow(final int id, final double x, final double y, final double z, final float width, final float height) {
		this.ukId = id;
		this.lastSeen = this.tickCount;
		if (Math.abs(width - this.entityData.get(WIDTH)) > 0.02F || Math.abs(height - this.entityData.get(HEIGHT)) > 0.02F) {
			this.entityData.set(WIDTH, width);
			this.entityData.set(HEIGHT, height);
			this.refreshDimensions();
		}

		this.setPos(x, y, z);
	}

	@Override
	protected void defineSynchedData(final SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(WIDTH, 0.6F);
		builder.define(HEIGHT, 1.8F);
	}

	@Override
	public void onSyncedDataUpdated(final EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (WIDTH.equals(key) || HEIGHT.equals(key)) {
			this.refreshDimensions();
		}
	}

	@Override
	protected EntityDimensions getDefaultDimensions(final Pose pose) {
		return EntityDimensions.scalable(this.entityData.get(WIDTH), this.entityData.get(HEIGHT));
	}

	@Override
	public void tick() {
		if (this.level().isClientSide()) {
			// the client's copy moves by what the server sends (crosshair picks use it)
			super.tick();
			return;
		}

		// no physics or AI of its own: it's where ULTRAKILL says; gone if ULTRAKILL stopped saying (the link went)
		if (this.tickCount - this.lastSeen > 40) {
			this.discard();
		}
	}

	/** Damage on it is damage on ULTRAKILL's enemy (from whatever hit it: a mob, an arrow, an explosion, the player). */
	@Override
	public boolean hurtServer(final ServerLevel level, final DamageSource source, final float amount) {
		UKPassthrough.UkEnemyHurt hurt = UKPassthrough.ukEnemyHurt;
		Entity by = source.getEntity();
		// only things that attack: not falls, walls, fire or lava where ULTRAKILL puts it (it has none of those there); and not the
		// player, whose V1 fights ULTRAKILL's enemies with ULTRAKILL's own weapons
		if (hurt == null || by == null || by == this || by instanceof Player || amount <= 0.0F || this.isRemoved()) {
			return false;
		}

		// as LivingEntity.hurtServer: within the cooldown only a bigger hit counts, for the difference (a sweep, a second arrow)
		float counts = amount;
		if (this.tickCount - this.lastHurtTick < HURT_COOLDOWN) {
			if (amount <= this.lastHurtAmount) {
				return false;
			}

			counts = amount - this.lastHurtAmount;
		} else {
			this.lastHurtTick = this.tickCount;
		}

		this.lastHurtAmount = amount;
		hurt.hurt(this, source, counts);
		return true;
	}

	/** Not under the crosshair (V1's hand can't pick it out, and it doesn't stand in front of blocks there)... */
	@Override
	public boolean isPickable() {
		return false;
	}

	/** ...but arrows and other projectiles hit it (Minecraft's own rule asks isPickable). */
	@Override
	public boolean canBeHitByProjectile() {
		return this.isAlive();
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public void push(final Entity entity) {
	}

	@Override
	public void push(final double x, final double y, final double z) {
	}

	@Override
	public boolean canBeCollidedWith(final Entity other) {
		return false;
	}

	@Override
	public boolean canBeAffected(final MobEffectInstance effect) {
		return false;
	}

	@Override
	public boolean isPushedByFluid() {
		return false;
	}

	@Override
	public boolean removeWhenFarAway(final double distance) {
		return false;
	}

	@Override
	public void checkDespawn() {
	}

	@Override
	public boolean canBeLeashed() {
		return false;
	}

	@Override
	public boolean canPickUpLoot() {
		return false;
	}

	@Override
	public boolean shouldDropExperience() {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}
}
