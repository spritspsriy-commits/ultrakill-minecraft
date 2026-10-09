package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.Explosions;
import dev.ukmc.ukpassthrough.client.Teleports;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server put the player somewhere (an end gateway, /tp): V1 goes there too. An explosion pushed the player: so is V1. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	// TAIL is only reached on the client thread (from the network thread the handler reschedules itself and throws)
	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void ukpassthrough$moved(final ClientboundPlayerPositionPacket packet, final CallbackInfo ci) {
		Teleports.onServerMoved();
	}

	@Inject(method = "handleExplosion", at = @At("TAIL"))
	private void ukpassthrough$exploded(final ClientboundExplodePacket packet, final CallbackInfo ci) {
		Explosions.onMinecraftExplosion(packet.playerKnockback());
	}
}
