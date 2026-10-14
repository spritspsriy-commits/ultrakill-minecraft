package dev.ukmc.ukpassthrough.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.ukmc.ukpassthrough.UKPassthrough;
import dev.ukmc.ukpassthrough.UkEnemy;
import dev.ukmc.ukpassthrough.client.mixin.BossHealthOverlayAccessor;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class UKPassthroughClient implements ClientModInitializer {
	private static final int GREEN = 0xFF55FF55;
	private static final int GOLD = 0xFFFFAA00;

	@Override
	@SuppressWarnings("deprecation") // EntityRendererRegistry: still Fabric API's way for an entity type of a mod's own
	public void onInitializeClient() {
		ModSettings.load();
		UkLink.launch();
		UKPassthrough.v1Hurt = MobHits::hurtV1;
		UKPassthrough.v1Heal = PlayerRules::healV1;
		UKPassthrough.v1Push = PlayerRules::pushV1;
		UKPassthrough.v1Strike = MobHits::struck;
		UKPassthrough.v1Boost = MobHits::boosted;
		UKPassthrough.boostedHit = Projectiles::boostedHit;
		UKPassthrough.v1Windup = MobHits::windup;
		UKPassthrough.v1Splat = MobHits::splatted;
		UKPassthrough.v1Void = Respawn::fellOutOfWorld;
		UKPassthrough.v1Feet = () -> {
			V1State.Pose p = V1State.live();
			return p == null ? null : new net.minecraft.world.phys.Vec3(p.fx(), p.fy(), p.fz());
		};
		FrameOverlay.init();
		UkEnemies.init();
		BlockBreaks.init();
		SettingsScreen.register();
		Progression.register();
		// the mod's settings (the terminal's loadout and colours too) saved as the game closes, not half a second after the last change
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> ModSettings.flush());
		// ULTRAKILL's enemies in Minecraft's world are only there for Minecraft's mobs: ULTRAKILL's picture shows them
		EntityRendererRegistry.register(UkEnemy.type(), NoopRenderer::new);
		ServerTickEvents.END_SERVER_TICK.register(Launches::serverTick);
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			Lag.tickStart();
			// also with the HUD hidden (F1), whose frame hook below then doesn't run
			HitStops.frame(minecraft);
			Launches.clientTick(minecraft);
			UkLink.get().tick();
			Teleports.tick();
			Control.tick(minecraft);
			Lag.mark("link and controls");
			BlockStream.tick(minecraft);
			Lag.mark("blocks");
			MobStream.tick(minecraft);
			Lag.mark("mobs");
			Projectiles.tick(minecraft);
			V1Light.tick(minecraft);
			PlayerRules.tick(minecraft);
			Fires.tick(minecraft);
			Punches.tick(minecraft);
			V1Hand.tick(minecraft);
			Portrait.tick();
			Lag.mark("projectiles, light, rules, hand");
			ModSettings.tick();
			Progression.tick(minecraft);
			GameSpeed.tick(minecraft);
			Lag.mark("settings and progression");
			DevWorld.tick(minecraft);
			DevDemo.tick(minecraft);
			Lag.mark("demo");
		});
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath(UKPassthrough.ID, "ultrakill"), FrameOverlay::extract);
		// ULTRAKILL's HUD and crosshair replace Minecraft's while V1 plays
		for (Identifier id : List.of(
			VanillaHudElements.CROSSHAIR, VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.HEALTH_BAR,
			VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR,
			VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP
		)) {
			HudElementRegistry.replaceElement(id, element -> (graphics, deltaTracker) -> {
				if (!Control.driving()) {
					element.extractRenderState(graphics, deltaTracker);
				}
			});
		}

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(UKPassthrough.ID, "status"), (graphics, deltaTracker) -> {
			UkLink link = UkLink.get();
			Minecraft minecraft = Minecraft.getInstance();
			Launches.frame();
			HitStops.frame(minecraft);
			Lag.frame();
			DevDemo.frame(minecraft, deltaTracker.getGameTimeDeltaPartialTick(true));
			if (!ModSettings.get().statusLine) {
				// the settings hide the line (round 25); the frame hooks above still run
				return;
			}

			String mode = !Control.v1Mode() ? " · Steve (F7: V1)" : Control.driving() ? " · V1 (F7: Steve)" : " · V1 coming…";
			String text = link.connected()
				? String.format(Locale.ROOT, "ULTRAKILL: connected · %.1f ms", Math.max(0.0, link.rttMillis())) + mode
				: "ULTRAKILL: waiting (127.0.0.1:" + link.getPort() + ")";
			// below the boss bars (the Ender Dragon's): they're 19 pixels apart from 12 down, each with its name above it
			int bosses = ((BossHealthOverlayAccessor) minecraft.gui.hud.getBossOverlay()).ukpassthrough$events().size();
			graphics.text(minecraft.font, text, 4, 4 + 19 * bosses, link.connected() ? GREEN : GOLD, true);
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
			literal("uk")
				.executes(c -> {
					UkLink link = UkLink.get();
					c.getSource().sendFeedback(Component.literal(link.connected()
						? "ULTRAKILL is connected (" + link.peerInfo() + "), " + BlockStream.sentCount() + " block sections sent"
							+ (V1State.fresh() ? String.format(Locale.ROOT, ", V1 at %.1f %.1f %.1f", V1State.x(), V1State.y(), V1State.z()) : "")
							+ (Progression.active() ? String.format(Locale.ROOT, ", Progression Mode: %,d P", Progression.p()) : "")
						: "ULTRAKILL isn't connected. Start it with BepInEx and the UKPassthrough plugin installed."));
					return 1;
				})
				.then(literal("ping").executes(c -> {
					UkLink link = UkLink.get();
					if (!link.connected()) {
						c.getSource().sendError(Component.literal("ULTRAKILL isn't connected"));
						return 0;
					}

					link.ping(rtt -> UkLink.chat(rtt < 0.0
						? "§cULTRAKILL didn't answer within 2 seconds"
						: String.format(Locale.ROOT, "§aULTRAKILL answered in %.1f ms", rtt)));
					return 1;
				}))
				.then(literal("say").then(argument("text", StringArgumentType.greedyString()).executes(c -> {
					UkLink link = UkLink.get();
					if (!link.connected()) {
						c.getSource().sendError(Component.literal("ULTRAKILL isn't connected"));
						return 0;
					}

					JsonObject m = UkLink.type("say");
					m.addProperty("text", StringArgumentType.getString(c, "text"));
					link.send(m);
					c.getSource().sendFeedback(Component.literal("Sent to ULTRAKILL"));
					return 1;
				})))
		));
	}
}
