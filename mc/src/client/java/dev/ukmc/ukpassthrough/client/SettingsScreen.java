package dev.ukmc.ukpassthrough.client;

import dev.ukmc.ukpassthrough.UKPassthrough;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * "ULTRAKILL x Minecraft Settings" (round 25, the user's request), opened from a button in Minecraft's pause menu. The user
 * named the blood stains toggle, potato mode (they chose two: potato mode and super potato mode), a button to ULTRAKILL's own
 * settings, "give all weapons" (in this mod only, never in their ULTRAKILL save) and "reset to default" (as things were when
 * the game started); the rest they picked from a list: ULTRAKILL's volume, sensitivity, field of view and screen shake, block
 * breaking, enemies and fights, camera and screen. Round 30 added "Balance..." ({@link BalanceScreen}) and Safe Building, on by default
 * (the user's request: blocks the player placed stand through the mod's explosions; {@link dev.ukmc.ukpassthrough.PlacedBlocks}). The
 * values live in {@link ModSettings}. Client thread.
 */
final class SettingsScreen extends OptionsSubScreen {
	private static final String KEY = "ukpassthrough.settings.";
	private static final int FULL_WIDTH = 204;

	private Button openUltrakill;
	private Button loadout;
	private Button useMine;
	private int builtUkVersion;
	private boolean builtUkConnected;
	/** Opened again on the next tick (not inside a button's own click). */
	private boolean reopen;
	/** Where the list was scrolled, for a menu opened again in its place. */
	private double startScroll;

	SettingsScreen(final Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable(KEY + "title"));
	}

	/** At start-up: the button in Minecraft's pause menu. */
	static void register() {
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
				addPauseButton(minecraft, pause);
			}
		});
	}

	/** Above "Save and Quit to Title" (the lowest full-width button), which moves down a row. */
	private static void addPauseButton(final Minecraft minecraft, final PauseScreen pause) {
		List<AbstractWidget> widgets = Screens.getWidgets(pause);
		AbstractWidget last = null;
		for (AbstractWidget w : widgets) {
			if (w instanceof Button && w.getWidth() >= FULL_WIDTH && (last == null || w.getY() > last.getY())) {
				last = w;
			}
		}

		int x = last != null ? last.getX() : pause.width / 2 - FULL_WIDTH / 2;
		int y = last != null ? last.getY() : pause.height / 4 + 120;
		if (last != null) {
			last.setY(y + 24);
		}

		widgets.add(Button.builder(Component.translatable(KEY + "title"), b -> minecraft.gui.setScreen(new SettingsScreen(pause)))
			.bounds(x, y, last != null ? last.getWidth() : FULL_WIDTH, 20).build());
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
		this.builtUkVersion = ModSettings.ukVersion();
		this.builtUkConnected = !Double.isNaN(ModSettings.uk("vol"));
		ModSettings.Values v = ModSettings.get();
		Options options = this.options;

		this.openUltrakill = Button.builder(Component.translatable(KEY + "open_ultrakill"), b -> this.openUltrakill()).width(310)
			.tooltip(Tooltip.create(Component.translatable(KEY + "open_ultrakill.tooltip"))).build();
		this.openUltrakill.active = Control.driving();
		this.list.addBig(this.openUltrakill);
		this.list.addBig(Button.builder(Component.translatable(KEY + "controls"), b -> this.minecraft.gui.setScreen(new ControlsScreen(this))).width(310)
			.tooltip(Tooltip.create(Component.translatable(KEY + "controls.tooltip"))).build());
		this.list.addBig(Button.builder(Component.translatable(KEY + "balance"), b -> this.minecraft.gui.setScreen(new BalanceScreen(this))).width(310)
			.tooltip(Tooltip.create(Component.translatable(KEY + "balance.tooltip"))).build());
		this.list.addSmall(
			toggle("potato", v.potato == Potato.LIGHT, on -> this.setPotato(on ? Potato.LIGHT : Potato.OFF)).createButton(options),
			toggle("super_potato", v.potato == Potato.SUPER, on -> this.setPotato(on ? Potato.SUPER : Potato.OFF)).createButton(options));
		this.list.addSmall(
			toggle("all_weapons", v.allWeapons, on -> this.setWeapons(on ? WeaponMode.GIVE : null, v.allWeapons && !on)).createButton(options),
			toggle("remove_weapons", v.removeWeapons, on -> this.setWeapons(on ? WeaponMode.REMOVE : null, v.removeWeapons && !on)).createButton(options));
		this.loadout = Button.builder(Component.translatable(KEY + "loadout"), b -> this.openTerminal()).width(150)
			.tooltip(Tooltip.create(Component.translatable(v.removeWeapons ? KEY + "loadout.removed" : KEY + "loadout.tooltip"))).build();
		this.useMine = Button.builder(Component.translatable(KEY + "loadout_mine"), b -> {
			ModSettings.clearLoadout();
			b.active = false;
		}).width(150).tooltip(Tooltip.create(Component.translatable(KEY + "loadout_mine.tooltip"))).build();
		this.loadout.active = Control.driving() && !v.removeWeapons;
		this.useMine.active = ModSettings.customLoadout();
		this.list.addSmall(this.loadout, this.useMine);
		this.list.addSmall(
			toggle("progression", v.progression, on -> this.setWeapons(on ? WeaponMode.PROGRESSION : null, v.progression && !on)).createButton(options),
			toggle("blood_stains", v.bloodStains, on -> v.bloodStains = on).createButton(options));
		// round 29: ULTRAKILL's cheats are only for creative, unless this says otherwise (the user's addition)
		this.list.addSmall(
			toggle("cheats", v.cheatsInSurvival, on -> v.cheatsInSurvival = on).createButton(options),
			toggle("safe_building", v.safeBuilding, on -> v.safeBuilding = on).createButton(options));

		this.list.addHeader(Component.translatable(KEY + "ultrakill"));
		this.list.addSmall(
			this.ukSlider("vol", "volume", 0, 100, 100.0, n -> n + "%"),
			this.ukSlider("sens", "sensitivity", 0, 100, 1.0, n -> Integer.toString(n)));
		this.list.addSmall(
			this.ukSlider("fov", "fov", 60, 160, 1.0, n -> Integer.toString(n)),
			this.ukSlider("shake", "shake", 0, 100, 100.0, n -> n + "%"));

		this.list.addHeader(Component.translatable(KEY + "blocks"));
		this.list.addSmall(
			slider("explosions", 0, 30, (int) Math.round(v.explosionStrength * 10), n -> n == 0 ? off() : (n * 10) + "%", n -> v.explosionStrength = n / 10.0).createButton(options),
			toggle("slam", v.slamBreaks, on -> v.slamBreaks = on).createButton(options));
		this.list.addSmall(List.of(toggle("glass", v.glassShatters, on -> v.glassShatters = on).createButton(options)));

		this.list.addHeader(Component.translatable(KEY + "enemies"));
		this.list.addSmall(
			slider("enemy_size", 30, 150, (int) Math.round(v.enemySize * 100), n -> n + "%", n -> v.enemySize = n / 100.0).createButton(options),
			toggle("fights", v.fights, on -> v.fights = on).createButton(options));
		this.list.addSmall(
			slider("v1_damage", 25, 400, (int) Math.round(v.v1Damage * 100), n -> n + "%", n -> v.v1Damage = n / 100.0).createButton(options),
			slider("damage_taken", 25, 400, (int) Math.round(v.damageTaken * 100), n -> n + "%", n -> v.damageTaken = n / 100.0).createButton(options));

		this.list.addHeader(Component.translatable(KEY + "camera"));
		this.list.addSmall(
			slider("back", 0, 60, (int) Math.round(v.shoulderBack * 10), SettingsScreen::blocks, n -> v.shoulderBack = n / 10.0).createButton(options),
			slider("right", -20, 20, (int) Math.round(v.shoulderRight * 10), n -> n == 0 ? "Middle" : n > 0 ? "Right " + blocks(n) : "Left " + blocks(-n),
				n -> v.shoulderRight = n / 10.0).createButton(options));
		this.list.addSmall(
			slider("up", -10, 20, (int) Math.round(v.shoulderUp * 10), SettingsScreen::blocks, n -> v.shoulderUp = n / 10.0).createButton(options),
			slider("front", 5, 60, (int) Math.round(v.frontDistance * 10), SettingsScreen::blocks, n -> v.frontDistance = n / 10.0).createButton(options));
		this.list.addSmall(
			toggle("status_line", v.statusLine, on -> v.statusLine = on).createButton(options),
			toggle("light", v.minecraftLight, on -> v.minecraftLight = on).createButton(options));
	}

	/** Development (the dev demo): the list scrolled to {@code amount} pixels (a big number: the bottom). */
	void scroll(final double amount) {
		this.list.setScrollAmount(amount);
	}

	/** Development (the dev demo): opened again on the next tick, as after a potato mode's button. */
	void reopenSoon() {
		this.reopen = true;
	}

	/** Development (the dev demo): the "Open ULTRAKILL Settings..." button. */
	Button openUltrakillButton() {
		return this.openUltrakill;
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable(KEY + "reset"), b -> this.confirmReset())
			.tooltip(Tooltip.create(Component.translatable(KEY + "reset.tooltip"))).width(150).build());
		footer.addChild(Button.builder(Component.translatable("gui.done"), b -> this.onClose()).width(150).build());
	}

	@Override
	public void tick() {
		super.tick();
		if (this.openUltrakill != null) {
			this.openUltrakill.active = Control.driving();
		}

		if (this.loadout != null) {
			this.loadout.active = Control.driving() && !ModSettings.get().removeWeapons;
			this.useMine.active = ModSettings.customLoadout();
		}

		// ULTRAKILL connected, or told new values of its own settings: show them
		if (this.reopen || ModSettings.ukVersion() != this.builtUkVersion || Double.isNaN(ModSettings.uk("vol")) == this.builtUkConnected) {
			this.reopen = false;
			this.reopen();
		}
	}

	/**
	 * The menu again, showing what changed: a fresh one, scrolled as this one was. Not Screen.rebuildWidgets: an OptionsSubScreen's
	 * init adds its title, list and footer to the same layout again, so the old list stayed on screen over the new one (round 25b,
	 * the user's report: after Reset to Default the sliders didn't move and the menu looked doubled).
	 */
	private void reopen() {
		SettingsScreen next = new SettingsScreen(this.lastScreen);
		next.startScroll = this.list.scrollAmount();
		this.minecraft.gui.setScreen(next);
	}

	@Override
	public void removed() {
		super.removed();
		ModSettings.flush();
	}

	private void setPotato(final int level) {
		int was = ModSettings.get().potato;
		ModSettings.setPotato(level);
		if (was != Potato.OFF && level != Potato.OFF) {
			// one potato mode on turns the other off: its button shows it
			this.reopen = true;
		}
	}

	/** The settings that decide V1's guns; only one of them is on at a time. */
	private enum WeaponMode {
		GIVE, REMOVE, PROGRESSION
	}

	/**
	 * Give all weapons, remove them, or Progression Mode (round 28): one turned on turns the others off, and their buttons show it
	 * (the menu opens again; round 25d). {@code off}: the one that was on was turned off ({@code mode} null).
	 */
	private void setWeapons(final WeaponMode mode, final boolean off) {
		ModSettings.Values v = ModSettings.get();
		int was = (v.allWeapons ? 1 : 0) + (v.removeWeapons ? 1 : 0) + (v.progression ? 1 : 0);
		if (mode == null && !off) {
			return;
		}

		v.allWeapons = mode == WeaponMode.GIVE;
		v.removeWeapons = mode == WeaponMode.REMOVE;
		v.progression = mode == WeaponMode.PROGRESSION;
		if (mode != null && was > 0) {
			this.reopen = true;
		}
	}

	/**
	 * "Change Loadout..." (round 27b, the user's words: "literally the same exact menu from the terminals in ultrakill", "as a menu you
	 * can click with your mouse"): the game goes back to V1, and ULTRAKILL opens its shop terminal's screen as a menu over the picture,
	 * with Minecraft's cursor as its pointer (uk/src/Terminal.cs).
	 */
	private void openTerminal() {
		this.minecraft.gui.setScreen(null);
		UkLink.get().send(UkLink.type("terminal"));
		UKPassthrough.LOG.info("settings: ULTRAKILL's shop terminal asked for");
	}

	/** ULTRAKILL's own settings, over Minecraft's picture: the game goes back to V1, and ULTRAKILL opens its pause menu's options. */
	private void openUltrakill() {
		this.minecraft.gui.setScreen(null);
		UkLink.get().send(UkLink.type("ukoptions"));
		UKPassthrough.LOG.info("settings: ULTRAKILL's own settings asked for");
	}

	private void confirmReset() {
		this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				ModSettings.reset();
			}

			this.minecraft.gui.setScreen(new SettingsScreen(this.lastScreen));
		}, Component.translatable(KEY + "reset.question"), Component.translatable(KEY + "reset.detail")));
	}

	/** One of ULTRAKILL's settings; greyed out while ULTRAKILL isn't connected (only it knows them). */
	private AbstractWidget ukSlider(final String name, final String key, final int min, final int max, final double scale, final IntFunction<String> label) {
		double value = ModSettings.uk(name);
		if (Double.isNaN(value)) {
			Button b = Button.builder(Component.translatable(KEY + key).append(": ").append(Component.translatable(KEY + "not_connected")), x -> {
			}).width(150).tooltip(Tooltip.create(Component.translatable(KEY + "not_connected.tooltip"))).build();
			b.active = false;
			return b;
		}

		int n = Math.clamp(Math.round(value * scale), min, max);
		return slider(key, min, max, n, label, i -> ModSettings.setUk(name, i / scale)).createButton(this.options);
	}

	static OptionInstance<Boolean> toggle(final String key, final boolean value, final BooleanSetter set) {
		return OptionInstance.createBoolean(KEY + key, OptionInstance.cachedConstantTooltip(Component.translatable(KEY + key + ".tooltip")), value, on -> {
			set.set(on);
			ModSettings.changed();
		});
	}

	static OptionInstance<Integer> slider(final String key, final int min, final int max, final int value, final IntFunction<String> label, final IntConsumer set) {
		return new OptionInstance<>(KEY + key, OptionInstance.cachedConstantTooltip(Component.translatable(KEY + key + ".tooltip")),
			(caption, n) -> Options.genericValueLabel(caption, Component.literal(label.apply(n))), new OptionInstance.IntRange(min, max, true),
			Math.clamp(value, min, max), n -> {
				set.accept(n);
				ModSettings.changed();
			});
	}

	/** Tenths of a block, as the slider shows them (the tooltip says they're blocks: "blocks" doesn't fit on the button). */
	private static String blocks(final int tenths) {
		return String.format(Locale.ROOT, "%.1f", tenths / 10.0);
	}

	private static String off() {
		return Component.translatable("options.off").getString();
	}

	@FunctionalInterface
	interface BooleanSetter {
		void set(boolean on);
	}
}
