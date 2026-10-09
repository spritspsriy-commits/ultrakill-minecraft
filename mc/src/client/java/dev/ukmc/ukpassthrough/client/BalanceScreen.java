package dev.ukmc.ukpassthrough.client;

import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * "Balance..." in the settings menu (round 30, the user's request: "a bunch of options to the ultrakill x minecraft settings that has to
 * do with balancing things, like changing what explodes certain materials, how fast you gain P, all that sorta stuff"). Asked, they chose
 * all four groups: P and prices, explosions on blocks (each kind's size and the toughest blocks it breaks), slams and mining, damage and
 * mobs. Each is a slider whose middle-of-the-road value is the mod's own; "Reset to Default" here puts only these back to the mod's own.
 * The values live in {@link ModSettings}. Client thread.
 */
final class BalanceScreen extends OptionsSubScreen {
	private static final String KEY = "ukpassthrough.settings.balance.";
	/** {@link Explosions#tier}'s rules, as the sliders and the log name them. */
	private static final String[] BREAKS = {"Nothing", "Soft Blocks", "Stone", "Iron Ore", "Diamond Ore", "Obsidian", "Like TNT"};
	private static final String[] PICKAXES = {"Off", "Wooden", "Stone", "Iron", "Diamond", "Netherite"};

	/** Where the list was scrolled, for the menu opened again after a reset. */
	private double startScroll;

	BalanceScreen(final Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable(KEY + "title"));
	}

	static String breaksName(final int breaks) {
		return BREAKS[Math.clamp(breaks, 0, BREAKS.length - 1)];
	}

	@Override
	protected void init() {
		super.init();
		if (this.startScroll > 0.0) {
			this.list.setScrollAmount(this.startScroll);
		}
	}

	@Override
	protected void addOptions() {
		ModSettings.Values v = ModSettings.get();
		Options options = this.options;

		this.list.addHeader(Component.translatable(KEY + "progress"));
		this.list.addSmall(
			slider("p_per_xp", 0, 100, v.pPerXp / 20, n -> String.format(Locale.ROOT, "%,d P", n * 20), n -> v.pPerXp = n * 20).createButton(options),
			slider("gun_prices", 1, 50, (int) Math.round(v.gunPrices * 10), BalanceScreen::tenths, n -> v.gunPrices = n / 10.0).createButton(options));

		this.list.addHeader(Component.translatable(KEY + "explosions"));
		for (Explosions.Kind kind : Explosions.Kind.values()) {
			String k = kind.key();
			this.list.addSmall(
				slider("size." + k, 0, 60, (int) Math.round(v.boomSize(kind) * 20), n -> n == 0 ? off() : (n * 5) + "%", n -> v.boomSize.put(k, n / 20.0)).createButton(options),
				slider("breaks." + k, Explosions.NOTHING, Explosions.LIKE_TNT, v.boomBreaks(kind), BalanceScreen::breaksName, n -> v.boomBreaks.put(k, n)).createButton(options));
		}

		this.list.addHeader(Component.translatable(KEY + "slams"));
		this.list.addSmall(
			slider("slam_height", 0, 60, (int) Math.round(v.slamHeight), n -> n == 0 ? "Any" : n + " blocks", n -> v.slamHeight = n).createButton(options),
			slider("slam_size", 1, 30, (int) Math.round(v.slamSize * 10), BalanceScreen::tenths, n -> v.slamSize = n / 10.0).createButton(options));
		this.list.addSmall(
			slider("punch_digging", 1, 20, (int) Math.round(v.punchDigging * 4), n -> (n * 25) + "%", n -> v.punchDigging = n / 4.0).createButton(options),
			slider("jackhammer", 0, PICKAXES.length - 1, v.jackhammer, n -> PICKAXES[n], n -> v.jackhammer = n).createButton(options));

		this.list.addHeader(Component.translatable(KEY + "mobs"));
		this.list.addSmall(
			slider("parry_damage", 0, 40, (int) Math.round(v.parryDamage * 10), BalanceScreen::tenths, n -> v.parryDamage = n / 10.0).createButton(options),
			slider("knockback", 0, 30, (int) Math.round(v.knockback * 10), BalanceScreen::tenths, n -> v.knockback = n / 10.0).createButton(options));
		this.list.addSmall(
			slider("windup", 0, 20, v.windupTicks, n -> String.format(Locale.ROOT, "%.2f s", n / 20.0), n -> v.windupTicks = n).createButton(options),
			slider("mob_reach", 1, 15, (int) Math.round(v.mobReach * 10), BalanceScreen::tenths, n -> v.mobReach = n / 10.0).createButton(options));
		this.list.addSmall(
			slider("enemy_damage", 0, 40, (int) Math.round(v.enemyDamage * 10), BalanceScreen::tenths, n -> v.enemyDamage = n / 10.0).createButton(options),
			slider("mobs_on_enemies", 0, 40, (int) Math.round(v.mobsOnEnemies * 10), BalanceScreen::tenths, n -> v.mobsOnEnemies = n / 10.0).createButton(options));
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable(KEY + "reset"), b -> this.confirmReset())
			.tooltip(Tooltip.create(Component.translatable(KEY + "reset.tooltip"))).width(150).build());
		footer.addChild(Button.builder(Component.translatable("gui.done"), b -> this.onClose()).width(150).build());
	}

	@Override
	public void removed() {
		super.removed();
		ModSettings.flush();
	}

	/** Development (the dev demo): the list scrolled to {@code amount} pixels (a big number: the bottom). */
	void scroll(final double amount) {
		this.list.setScrollAmount(amount);
	}

	private void confirmReset() {
		double scroll = this.list.scrollAmount();
		this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				ModSettings.resetBalance();
			}

			BalanceScreen next = new BalanceScreen(this.lastScreen);
			next.startScroll = scroll;
			this.minecraft.gui.setScreen(next);
		}, Component.translatable(KEY + "reset.question"), Component.translatable(KEY + "reset.detail")));
	}

	private static net.minecraft.client.OptionInstance<Integer> slider(final String key, final int min, final int max, final int value, final IntFunction<String> label, final IntConsumer set) {
		return SettingsScreen.slider("balance." + key, min, max, value, label, set);
	}

	/** Tenths as a percentage: 10 is "100%". */
	private static String tenths(final int n) {
		return (n * 10) + "%";
	}

	private static String off() {
		return Component.translatable("options.off").getString();
	}
}
