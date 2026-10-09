package dev.ukmc.ukpassthrough.mixin;

import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sounds and level events (a block breaking: its sound and particles) that Minecraft sends to everyone except the player
 * who caused them, because that player's client has already played them. While V1's punch uses or breaks a block
 * ({@link UKPassthrough#punchingPlayer}) nothing went through the client, so the player gets them as well: the door's
 * creak, the button's click, the block's break.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
	@Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$levelEventForV1(final Entity except, final int type, final BlockPos pos, final int data, final CallbackInfo ci) {
		if (except != null && except == UKPassthrough.punchingPlayer) {
			ci.cancel();
			((ServerLevel) (Object) this).levelEvent(null, type, pos, data);
		}
	}

	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$soundForV1(final Entity except, final double x, final double y, final double z, final Holder<SoundEvent> sound, final SoundSource source, final float volume,
		final float pitch, final long seed, final CallbackInfo ci) {
		if (except != null && except == UKPassthrough.punchingPlayer) {
			ci.cancel();
			((ServerLevel) (Object) this).playSeededSound(null, x, y, z, sound, source, volume, pitch, seed);
		}
	}

	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$entitySoundForV1(final Entity except, final Entity from, final Holder<SoundEvent> sound, final SoundSource source, final float volume, final float pitch, final long seed,
		final CallbackInfo ci) {
		if (except != null && except == UKPassthrough.punchingPlayer) {
			ci.cancel();
			((ServerLevel) (Object) this).playSeededSound(null, from, sound, source, volume, pitch, seed);
		}
	}
}
