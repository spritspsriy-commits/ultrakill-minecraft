package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.MobReach;
import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While V1 moves the player, neither side collides the player with blocks: V1's own physics already did, and
 * V1 fits where Steve doesn't (it slides under one-block gaps). With no physics a player is also never
 * "in a wall", so that doesn't suffocate Steve either.
 * <p>
 * And the player's damage goes to V1: Steve's health stays full. Damage V1 doesn't take in ULTRAKILL (falling, drowning,
 * starving, the void, being squeezed into blocks) is dropped. The rest goes through Minecraft's own steps first, as it
 * would for Steve: armor (which wears it), Resistance and protection enchantments, then absorption hearts (golden
 * apples, the totem). Poison's damage is marked as unable to kill.
 * <p>
 * The player's own attacks while V1 drives (the weapon in V1's hand: a swing with its sweep, the mace, a spear's stab) are
 * marked while they run, so the mobs they hurt go to ULTRAKILL for blood and style points (LivingEntityMixin).
 */
@Mixin(Player.class)
abstract class PlayerMixin {
	/** An orb's experience in Progression Mode becomes ULTRAKILL's P instead (round 28, {@link UKPassthrough#xpPickup}). */
	@Inject(method = "giveExperiencePoints", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$experienceToP(final int points, final CallbackInfo ci) {
		UKPassthrough.XpPickup pickup = UKPassthrough.xpPickup;
		if (UKPassthrough.orbTouch && points > 0 && pickup != null && pickup.take(points)) {
			ci.cancel();
		}
	}

	/** While V1 lies dead, Steve (standing where it fell) touches nothing: the items it dropped aren't picked up again (round 29). */
	@Inject(method = "touch", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$nothingWhileDead(final Entity entity, final CallbackInfo ci) {
		if (UKPassthrough.v1Dead && UKPassthrough.v1Driving && (Object) this instanceof ServerPlayer) {
			ci.cancel();
		}
	}

	@Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void ukpassthrough$ghost(final CallbackInfo ci) {
		if (UKPassthrough.v1Driving) {
			((Entity) (Object) this).noPhysics = true;
			// V1's health is the one that counts; at full health Steve's food doesn't heal him, which would heal V1
			// (LivingEntityMixin), and the totem's half heart doesn't stay
			if ((Object) this instanceof ServerPlayer player && player.isAlive() && player.getHealth() < player.getMaxHealth()) {
				player.setHealth(player.getMaxHealth());
			}
		}
	}

	/**
	 * The server doesn't move the player while V1 drives: V1 does. Its own physics would, in the middle of the tick, with
	 * nothing to stand on: Steve fell by all the speed he had built up (blocks a tick) before the server looked for items
	 * to pick up and blocks he's in, and was put back after. He picked up nothing at V1's height.
	 */
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$stayOnV1(final Vec3 input, final CallbackInfo ci) {
		if (UKPassthrough.v1Driving && (Object) this instanceof ServerPlayer) {
			ci.cancel();
		}
	}

	@Inject(method = "attack", at = @At("HEAD"))
	private void ukpassthrough$attackStarts(final Entity target, final CallbackInfo ci) {
		this.ukpassthrough$strikeStarts();
	}

	@Inject(method = "attack", at = @At("RETURN"))
	private void ukpassthrough$attackEnds(final Entity target, final CallbackInfo ci) {
		UKPassthrough.striking = null;
	}

	@Inject(method = "stabAttack", at = @At("HEAD"))
	private void ukpassthrough$stabStarts(final EquipmentSlot slot, final Entity target, final float damage, final boolean dealsDamage, final boolean dealsKnockback,
		final boolean dismounts, final CallbackInfoReturnable<Boolean> cir) {
		this.ukpassthrough$strikeStarts();
	}

	@Inject(method = "stabAttack", at = @At("RETURN"))
	private void ukpassthrough$stabEnds(final EquipmentSlot slot, final Entity target, final float damage, final boolean dealsDamage, final boolean dealsKnockback,
		final boolean dismounts, final CallbackInfoReturnable<Boolean> cir) {
		UKPassthrough.striking = null;
	}

	private void ukpassthrough$strikeStarts() {
		if (UKPassthrough.v1Driving && (Object) this instanceof ServerPlayer player) {
			UKPassthrough.striking = player;
		}
	}

	// after hurtServer's checks (creative, difficulty scaling, shields, the hurt cooldown), before health changes
	@Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$toV1(final ServerLevel level, final DamageSource source, final float amount, final CallbackInfo ci) {
		if (!UKPassthrough.v1Driving || !((Object) this instanceof ServerPlayer player)) {
			return;
		}

		ci.cancel();
		if (source.is(DamageTypes.FALL) || source.is(DamageTypes.DROWN) || source.is(DamageTypes.STARVE) || source.is(DamageTypes.IN_WALL)
			|| source.is(DamageTypes.CRAMMING) || source.is(DamageTypes.FLY_INTO_WALL) || source.is(DamageTypes.FELL_OUT_OF_WORLD)
			|| player.isInvulnerableTo(level, source)) {
			return;
		}

		// as Player.actuallyHurt does
		LivingEntityInvoker steps = (LivingEntityInvoker) player;
		float left = steps.ukpassthrough$afterMagic(source, steps.ukpassthrough$afterArmor(source, amount));
		float absorbed = Math.min(player.getAbsorptionAmount(), left);
		if (absorbed > 0.0F) {
			player.setAbsorptionAmount(player.getAbsorptionAmount() - absorbed);
			left -= absorbed;
		}

		String type = source.typeHolder().getRegisteredName();
		if (source.getDirectEntity() instanceof Mob mob) {
			// a mob's melee: how far from it V1 really was, against where the server had the player
			AABB v1 = MobReach.v1Box(player);
			UKPassthrough.LOG.info("melee hit from {} {}: V1 {} blocks past its box, the player on the server {} (a mob reaches V1 {}, Steve {})", type, mob.getId(),
				v1 == null ? "?" : String.format(java.util.Locale.ROOT, "%.2f", MobReach.gap(mob, v1)), String.format(java.util.Locale.ROOT, "%.2f", MobReach.gap(mob, player.getBoundingBox())),
				String.format(java.util.Locale.ROOT, "%.2f", MobReach.REACH * MobReach.v1Share), String.format(java.util.Locale.ROOT, "%.2f", MobReach.REACH));
		}

		UKPassthrough.LOG.info("player damage {} ({}) goes to V1: {} after armor, effects and absorption{}", amount, type, left, UKPassthrough.poisonTick ? ", can't kill" : "");
		UKPassthrough.V1Hurt hurt = UKPassthrough.v1Hurt;
		if (hurt != null && left > 0.0F) {
			// the projectile itself, so ULTRAKILL can tell a hit from one V1 parries in time or a moment late
			hurt.hurt(left, type, source.is(DamageTypeTags.IS_EXPLOSION), source.getDirectEntity(), UKPassthrough.poisonTick);
		}
	}
}
