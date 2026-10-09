package dev.ukmc.ukpassthrough.client;

import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Development only: with -Dukpassthrough.devWorld=NAME the client opens that world from the title screen by
 * itself (creating a normal survival world with a fixed seed the first time), so a test run needs no clicks.
 * The launcher installation never sets it.
 */
final class DevWorld {
	private static final String NAME = System.getProperty("ukpassthrough.devWorld");
	private static final long SEED = 20261003L;
	private static boolean requested;

	private DevWorld() {
	}

	/** The dev demo left the world: it's opened again from the title screen. */
	static void reopen() {
		requested = false;
	}

	static void tick(final Minecraft minecraft) {
		if (NAME == null || requested || minecraft.level != null || !(minecraft.gui.screen() instanceof TitleScreen)) {
			return;
		}

		requested = true;
		if (minecraft.getLevelSource().levelExists(NAME)) {
			UKPassthrough.LOG.info("dev: opening world {}", NAME);
			minecraft.createWorldOpenFlows().openWorld(NAME, () -> minecraft.gui.setScreen(new TitleScreen()));
		} else {
			UKPassthrough.LOG.info("dev: creating world {}", NAME);
			LevelSettings settings = new LevelSettings(NAME, GameType.SURVIVAL, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
			minecraft.createWorldOpenFlows().createFreshLevel(NAME, settings, new WorldOptions(SEED, true, false), WorldPresets::createNormalWorldDimensions, minecraft.gui.screen());
		}
	}
}
