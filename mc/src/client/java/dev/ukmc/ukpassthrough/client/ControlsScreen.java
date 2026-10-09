package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The controls guide (round 25c, the user's request: "a control guide button in the ultrakill x minecraft menu that will tell you
 * what all the controls do for like inventory and all that stuff"), from the settings menu. ULTRAKILL's keys are the player's
 * own bindings: asked for when the guide opens ({"t":"askbinds"}; ULTRAKILL answers {"t":"binds","k":{...}}, uk/src/Settings.cs),
 * and the guide opens again when they come. Without ULTRAKILL it says "ULTRAKILL's key" for them. The mod's own keys (F7, Tab, R
 * when V1 is dead, Esc, T and /) are fixed in the mod; the perspective key is Minecraft's own binding. Client thread.
 */
final class ControlsScreen extends Screen {
	private static final int TEXT_WIDTH = 340;

	/** ULTRAKILL's keys as it last said (link thread), and how often it has. */
	private static volatile JsonObject keys;
	private static volatile int keysVersion;

	private final Screen lastScreen;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private ScrollableLayout body;
	private int builtVersion;
	/** ULTRAKILL was asked for its keys already (this guide was opened again with them). */
	private boolean asked;

	ControlsScreen(final Screen lastScreen) {
		super(Component.literal("Controls Guide"));
		this.lastScreen = lastScreen;
	}

	/** From the link thread: {"t":"binds","k":{name: keys}}. */
	static void onBinds(final JsonObject m) {
		keys = m.getAsJsonObject("k");
		keysVersion++;
	}

	@Override
	protected void init() {
		this.builtVersion = keysVersion;
		UkLink link = UkLink.get();
		if (link != null && link.connected() && !this.asked) {
			link.send(UkLink.type("askbinds"));
		}

		this.layout.addToHeader(new StringWidget(this.title, this.font));
		LinearLayout content = LinearLayout.vertical().spacing(4);
		content.defaultCellSetting().alignHorizontallyLeft();
		boolean known = link != null && link.connected() && keys != null && keys.size() > 0;

		this.header(content, "Playing as V1");
		if (!known) {
			this.note(content, "Start ULTRAKILL to see your own keys here; until then they're called \"ULTRAKILL's key\". You change them in ULTRAKILL's options (Controls).");
		}

		this.line(content, uk("move", known), "move");
		this.line(content, uk("jump", known), "jump; next to a wall, wall jump");
		this.line(content, uk("dash", known), "dash");
		this.line(content, uk("slide", known), "slide; in the air, ground slam (it cracks dirt and other soft blocks and breaks glass and ice under you, from 20 blocks up it breaks them at once)");
		this.line(content, uk("fire1", known) + " / " + uk("fire2", known), "fire / the weapon's other fire");
		this.line(content, uk("slot1", known) + " to " + uk("slot5", known), "revolver, shotgun, nailgun, railcannon, rocket launcher (again: its next variant)");
		this.line(content, uk("fire1", known) + " with the Jackhammer", "a strike at a block within 4 blocks mines it at once, as an iron pickaxe would (stone, ores, wood, dirt; not obsidian)");
		this.line(content, uk("variation", known), "the next variant of the weapon you hold");
		this.line(content, uk("slot6", known), "the Sandbox arm: spawn ULTRAKILL's enemies (its menu takes your mouse), destroy, move (in creative, or with Cheats in Survival on)");
		this.line(content, uk("feedbacker", known), "punch with the blue arm (Feedbacker): parries arrows, fireballs and other projectiles, and a mob's hit while it flashes yellow; uses doors, levers, chests and the like; right after you shoot an arrow, boosts it");
		this.line(content, uk("knuckleblaster", known), "punch with the red arm (Knuckleblaster): breaks blocks you could break by hand (dirt at once, wood in three punches); held, its blast breaks a small hole");
		this.line(content, uk("changefist", known), "change arm");
		this.line(content, uk("whiplash", known), "Whiplash: pulls small mobs to you and you to big ones");
		this.line(content, "R", "when V1 is dead: come back where Minecraft would respawn you (a click works too); your items dropped where V1 died, as in Minecraft");
		this.line(content, "Home or `", "ULTRAKILL's cheats menu, and its cheat keys: only in creative, unless Cheats in Survival is on");

		this.header(content, "Minecraft's keys while you're V1");
		this.line(content, "F7", "switch between V1 and Steve");
		this.line(content, "Tab", "open and close the inventory");
		this.line(content, this.minecraft.options.keyTogglePerspective.getTranslatedKeyMessage().getString(), "first person, behind V1, in front of V1");
		this.line(content, "Esc", "Minecraft's pause menu, with ULTRAKILL x Minecraft Settings in it");
		this.line(content, "T and /", "chat and commands");
		this.line(content, "F1 to F12", "Minecraft's own (F2: screenshot, F3: debug screen)");

		this.header(content, "The inventory and V1's blue hand");
		this.line(content, "Click an item", "the inventory closes and V1 holds it in its blue hand (a block like a skull, anything else on the left like Minecraft's offhand)");
		this.line(content, "Hold and drag", "move an item to another slot; let go outside the inventory window to throw it");
		this.line(content, uk("feedbacker", known), "use what V1 holds as Steve would: place blocks, swing swords and axes, mine while held, eat, fill and pour buckets, draw a bow (let go to shoot); parries still work");
		this.line(content, uk("knuckleblaster", known) + " or " + uk("changefist", known), "empty V1's hand (the item stays in your inventory)");

		this.header(content, "ULTRAKILL's own menus");
		this.line(content, "Mouse", "in the Sandbox arm's spawn menu and in ULTRAKILL's settings (Esc, ULTRAKILL x Minecraft Settings, Open ULTRAKILL Settings) your cursor points and clicks");
		this.line(content, "Esc", "close ULTRAKILL's menu and play again");
		this.line(content, "Change Loadout...", "(in ULTRAKILL x Minecraft Settings) ULTRAKILL's shop terminal opens as a menu: click its buttons with your mouse; Esc closes it");

		this.header(content, "Commands");
		this.line(content, "/uk", "whether ULTRAKILL is connected, and where V1 is");
		this.line(content, "/uk ping", "how fast the two games talk");
		this.line(content, "/uk say <text>", "show the text in ULTRAKILL's corner");

		this.header(content, "In ULTRAKILL's own window (only if you click into it)");
		this.line(content, "F8", "say hello to Minecraft's chat");
		this.line(content, "F9", "show or hide the grey blocks while V1 mode is off");

		this.body = new ScrollableLayout(this.minecraft, content, this.layout.getContentHeight());
		this.layout.addToContents(this.body);
		this.layout.addToFooter(Button.builder(Component.translatable("gui.done"), b -> this.onClose()).width(200).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		if (this.body != null) {
			this.body.arrangeElements();
			this.body.setMaxHeight(this.layout.getContentHeight());
		}

		this.layout.arrangeElements();
	}

	@Override
	public void tick() {
		super.tick();
		if (keysVersion != this.builtVersion) {
			// ULTRAKILL's keys came (a moment after it opened): the guide again with them
			ControlsScreen next = new ControlsScreen(this.lastScreen);
			next.asked = true;
			this.minecraft.gui.setScreen(next);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.lastScreen);
	}

	/** ULTRAKILL's key for an action, as its options name it ("or" between two; "no key" for one left unbound there). */
	private static String uk(final String action, final boolean known) {
		JsonObject k = keys;
		if (!known || k == null || !k.has(action)) {
			return "ULTRAKILL's key";
		}

		if (k.get(action).getAsString().isBlank()) {
			return "(no key)";
		}

		return k.get(action).getAsString().replace(" | ", " or ");
	}

	private void header(final LinearLayout content, final String text) {
		content.addChild(new SpacerElement(TEXT_WIDTH, 4));
		content.addChild(new StringWidget(Component.literal(text).withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD), this.font));
	}

	private void note(final LinearLayout content, final String text) {
		content.addChild(new MultiLineTextWidget(Component.literal(text).withStyle(ChatFormatting.GRAY), this.font).setMaxWidth(TEXT_WIDTH));
	}

	private void line(final LinearLayout content, final String key, final String what) {
		MutableComponent c = Component.literal(key).withStyle(ChatFormatting.YELLOW).append(Component.literal(": " + what).withStyle(ChatFormatting.WHITE));
		content.addChild(new MultiLineTextWidget(c, this.font).setMaxWidth(TEXT_WIDTH));
	}

	/** Development (the dev demo): the guide scrolled down by {@code amount} wheel notches. */
	void scroll(final double amount) {
		this.mouseScrolled(this.width / 2.0, this.height / 2.0, 0.0, -amount);
	}
}
