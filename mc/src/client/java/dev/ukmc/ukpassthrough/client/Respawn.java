package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ukmc.ukpassthrough.UKPassthrough;
import dev.ukmc.ukpassthrough.mixin.LivingEntityInvoker;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;

/**
 * V1 came back after dying in ULTRAKILL ({"t":"respawn"}): Steve goes where Minecraft itself would respawn him, his
 * bed or respawn anchor if he has one (an anchor uses a charge, as on a real respawn), otherwise the world spawn, in
 * whatever dimension that is. The answer ({"t":"respawned","p":[x, y, z],"r":[yaw, pitch]}) is where ULTRAKILL puts
 * V1, once the ground there has arrived. Steve never dies himself: V1 takes his damage.
 */
final class Respawn {
	/** When V1 was last told it fell out of the world (System.nanoTime; the void hurts every tick it's down there). */
	private static long voidSentAt;

	private Respawn() {
	}

	/**
	 * Server thread (PlayerMixin): the player is in the void under the world while V1 drives (round 33, a player's report: "when the player
	 * falls into the void the game freaks out a bit"): V1 dies there as Steve would ({"t":"void"}), at most once a second.
	 */
	static void fellOutOfWorld() {
		long now = System.nanoTime();
		if (now - voidSentAt < 1_000_000_000L || UKPassthrough.v1Dead) {
			return;
		}

		voidSentAt = now;
		UkLink.get().send(UkLink.type("void"));
		UKPassthrough.LOG.info("the player fell out of the world: V1 dies, as Steve would");
	}

	/** From the link thread. */
	static void onRespawn(final JsonObject m) {
		UKPassthrough.v1Dead = false;
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}

		// Steve stops following V1's last pose where it died (V1 waits in ULTRAKILL until it's put at the new spot)
		minecraft.execute(V1State::clear);
		server.execute(() -> respawn(server));
	}

	/**
	 * From the link thread: {"t":"died"}, V1 died in ULTRAKILL (round 29, the user's request: "When v1 dies, you should drop all of your
	 * minecraft items like in minecraft"). The player's items drop where it fell, as Minecraft drops them when a player dies: unless the
	 * keepInventory game rule is on, curse of vanishing items vanish and the rest scatter on the ground. Experience is kept (the user's
	 * choice).
	 */
	static void onDied(final JsonObject m) {
		// until V1 comes back, Steve (standing where it fell) picks none of it up again
		UKPassthrough.v1Dead = true;
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server != null) {
			server.execute(() -> dropItems(server));
		}
	}

	/** Server thread. */
	private static void dropItems(final IntegratedServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			return;
		}

		ServerPlayer player = players.getFirst();
		if (player.isCreative() || player.isSpectator()) {
			return;
		}

		ServerLevel level = player.level();
		if (level.getGameRules().get(GameRules.KEEP_INVENTORY)) {
			UKPassthrough.LOG.info("V1 died: the keepInventory rule is on, the player keeps the items");
			return;
		}

		int stacks = 0;
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			stacks += inventory.getItem(i).isEmpty() ? 0 : 1;
		}

		((LivingEntityInvoker) player).ukpassthrough$dropEquipment(level);
		UKPassthrough.LOG.info(String.format(Locale.ROOT, "V1 died: %d stacks dropped at %.1f %.1f %.1f in %s", stacks, player.getX(), player.getY(), player.getZ(),
			level.dimension().identifier()));
	}

	/** Server thread. */
	private static void respawn(final IntegratedServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			return;
		}

		ServerPlayer player = players.getFirst();
		TeleportTransition to = player.findRespawnPositionAndUseSpawnBlock(true, TeleportTransition.DO_NOTHING);
		player.teleport(to);
		player.resetFallDistance();
		player.clearFire();
		UKPassthrough.LOG.info(String.format(Locale.ROOT, "V1 respawns at %.1f %.1f %.1f in %s", player.getX(), player.getY(), player.getZ(), player.level().dimension().identifier()));
		JsonObject answer = UkLink.type("respawned");
		JsonArray p = new JsonArray();
		p.add(player.getX());
		p.add(player.getY());
		p.add(player.getZ());
		answer.add("p", p);
		JsonArray r = new JsonArray();
		r.add(Mth.positiveModulo(player.getYRot(), 360.0F));
		r.add(player.getXRot());
		answer.add("r", r);
		UkLink.get().send(answer);
	}
}
