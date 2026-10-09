package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ukmc.ukpassthrough.UkEnemy;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

/**
 * The living things around the player go to ULTRAKILL every tick (20 a second), where each becomes an
 * invisible enemy V1 can hit: {"t":"mobs","m":[id, x, y, z, width, height, eye height, health, flags, ...]}, feet
 * position in blocks. Dying mobs come with health 0 until they're gone. Flags: 1 = on fire (it then lights ULTRAKILL's
 * gasoline), 2 = the Feedbacker uses it instead of hitting it ({@link Punches#usable}). Client thread.
 */
final class MobStream {
	/** About as far as V1 can reliably hit something. */
	private static final double RANGE = 48.0;
	private static boolean sentAny;

	private MobStream() {
	}

	static void tick(final Minecraft minecraft) {
		UkLink link = UkLink.get();
		if (!link.connected() || minecraft.level == null || minecraft.player == null) {
			sentAny = false;
			return;
		}

		List<LivingEntity> mobs = minecraft.level.getEntitiesOfClass(LivingEntity.class, minecraft.player.getBoundingBox().inflate(RANGE),
			e -> !(e instanceof Player) && !(e instanceof ArmorStand) && !(e instanceof UkEnemy) && !e.isRemoved());
		if (mobs.isEmpty() && !sentAny) {
			return;
		}

		JsonArray m = new JsonArray();
		for (LivingEntity e : mobs) {
			m.add(e.getId());
			m.add(round(e.getX()));
			m.add(round(e.getY()));
			m.add(round(e.getZ()));
			m.add(e.getBbWidth());
			m.add(e.getBbHeight());
			m.add(e.getEyeHeight());
			m.add(e.isDeadOrDying() ? 0.0F : e.getHealth());
			m.add((e.isOnFire() ? 1 : 0) | (Punches.usable(e, minecraft.player) ? 2 : 0));
		}

		JsonObject message = UkLink.type("mobs");
		message.add("m", m);
		link.send(message);
		// one empty list after the last mob goes, then quiet
		sentAny = !mobs.isEmpty();
	}

	private static double round(final double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}
}
