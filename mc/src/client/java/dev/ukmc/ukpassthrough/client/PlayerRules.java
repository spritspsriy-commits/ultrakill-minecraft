package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ukmc.ukpassthrough.UKPassthrough;
import dev.ukmc.ukpassthrough.mixin.LivingEntityInvoker;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft's rules for the player that V1 follows while it drives (uk/src/PlayerRules.cs on the other side):
 * <ul>
 * <li>{"t":"body","spd","jmp","grav","eff","lev","slowfall","totem"}, on a change and once a second: how fast the player
 * walks (Speed, Slowness, Soul Speed; the movement speed attribute against its usual 0.1, without sprinting), how high it
 * jumps (Jump Boost; jump strength and the boost against the usual 0.42), its gravity against the usual 0.08, its
 * movement efficiency (Soul Speed: soul sand doesn't slow it), Levitation's level (0 for none), Slow Falling, and whether
 * either hand holds a totem of undying.</li>
 * <li>{"t":"heal","hp":V1 hit points}: the player's healing (instant health, regeneration; LivingEntityMixin and
 * RegenerationMobEffectMixin), times {@link MobHits#V1_DAMAGE} as damage is.</li>
 * <li>{"t":"totem"} from ULTRAKILL: V1 would have died with a totem in Steve's hand. Minecraft uses the totem as for a
 * death (the item goes, the totem's animation and sound, Regeneration II, Absorption II and Fire Resistance).</li>
 * <li>Knockback on the player (a mob's hit, an arrow's) goes to V1 as an explosion's push does ({"t":"push"}; from
 * LivingEntityMixin, {@link #pushV1}).</li>
 * <li>Strength and Weakness change V1's damage to mobs ({@link #attackFactor}).</li>
 * </ul>
 */
public final class PlayerRules {
	/** Strength: each level adds this much to V1's damage; Weakness: each level takes this much off (at most down to a quarter). */
	private static final float STRENGTH_PER_LEVEL = 0.3F;
	private static final float WEAKNESS_PER_LEVEL = 0.25F;
	private static String sent = "";
	private static int quietTicks;

	private PlayerRules() {
	}

	/** Client thread, every tick. */
	static void tick(final Minecraft minecraft) {
		LocalPlayer p = minecraft.player;
		if (p == null || !Control.driving()) {
			sent = "";
			return;
		}

		double speed = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
		if (p.isSprinting()) {
			// sprinting is a ×1.3 on top, and V1 has its own way of going fast
			speed /= 1.3;
		}

		MobEffectInstance levitation = p.getEffect(MobEffects.LEVITATION);
		JsonObject m = UkLink.type("body");
		m.addProperty("spd", round(speed / 0.1));
		m.addProperty("jmp", round((p.getAttributeValue(Attributes.JUMP_STRENGTH) + p.getJumpBoostPower()) / 0.42));
		m.addProperty("grav", round(p.getAttributeValue(Attributes.GRAVITY) / 0.08));
		m.addProperty("eff", round(p.getAttributeValue(Attributes.MOVEMENT_EFFICIENCY)));
		m.addProperty("lev", levitation == null ? 0 : levitation.getAmplifier() + 1);
		m.addProperty("slowfall", p.hasEffect(MobEffects.SLOW_FALLING));
		m.addProperty("totem", p.getMainHandItem().has(DataComponents.DEATH_PROTECTION) || p.getOffhandItem().has(DataComponents.DEATH_PROTECTION));
		// whom ULTRAKILL's own enemies go for (Infighting.cs): V1 in survival, Minecraft's mobs in creative
		if (minecraft.gameMode != null) {
			m.addProperty("gm", minecraft.gameMode.getPlayerMode().getSerializedName());
		}
		String text = m.toString();
		if (text.equals(sent) && ++quietTicks < 20) {
			return;
		}

		if (!text.equals(sent)) {
			UKPassthrough.LOG.info("player's rules for V1: {}", text);
		}

		sent = text;
		quietTicks = 0;
		UkLink.get().send(m);
	}

	private static double round(final double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}

	/** Server thread (LivingEntityMixin, RegenerationMobEffectMixin): the player's healing, for V1. */
	static void healV1(final float amount) {
		JsonObject m = UkLink.type("heal");
		m.addProperty("hp", Math.max(1, Math.round(amount * MobHits.V1_DAMAGE)));
		UkLink.get().send(m);
		UKPassthrough.LOG.info("player healing {} goes to V1", amount);
	}

	/** From the link thread: V1 would have died, and Steve holds a totem. */
	static void onTotem(final JsonObject m) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return;
		}

		server.execute(() -> {
			List<ServerPlayer> players = server.getPlayerList().getPlayers();
			if (players.isEmpty()) {
				return;
			}

			ServerPlayer player = players.getFirst();
			boolean used = ((LivingEntityInvoker) player).ukpassthrough$useTotem(player.damageSources().generic());
			UKPassthrough.LOG.info("V1's totem of undying: {}", used ? "used" : "none in the player's hands any more");
		});
	}

	/** Server thread (MobHits): what Strength and Weakness do to V1's damage. */
	static float attackFactor(final LivingEntity player) {
		MobEffectInstance strength = player.getEffect(MobEffects.STRENGTH);
		MobEffectInstance weakness = player.getEffect(MobEffects.WEAKNESS);
		float f = 1.0F + (strength == null ? 0.0F : STRENGTH_PER_LEVEL * (strength.getAmplifier() + 1)) - (weakness == null ? 0.0F : WEAKNESS_PER_LEVEL * (weakness.getAmplifier() + 1));
		return Math.max(0.25F, f);
	}

	/** Server thread (LivingEntityMixin): knockback on the player, for V1. */
	static void pushV1(final double x, final double y, final double z, final String why) {
		push(new Vec3(x, y, z), why);
	}

	/** A push for V1, in blocks a tick, as Minecraft adds it to the player's motion. */
	static void push(final Vec3 v, final String why) {
		JsonObject m = UkLink.type("push");
		JsonArray a = new JsonArray();
		a.add(Math.round(v.x * 1000.0) / 1000.0);
		a.add(Math.round(v.y * 1000.0) / 1000.0);
		a.add(Math.round(v.z * 1000.0) / 1000.0);
		m.add("v", a);
		UkLink.get().send(m);
		UKPassthrough.LOG.info(String.format(Locale.ROOT, "%s (%.2f %.2f %.2f): goes to V1", why, v.x, v.y, v.z));
	}
}
