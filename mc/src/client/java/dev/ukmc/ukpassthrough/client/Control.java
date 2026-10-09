package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Who has the controls. In V1 mode (the default; F7 toggles it) ULTRAKILL moves the player and the camera and
 * gets the keyboard and mouse; Minecraft keeps Esc, T, / and F1-F12, its perspective key wherever it's bound, and Tab for the
 * inventory ({@link V1Inventory}).
 * With V1 mode off you are plain Steve, and V1 waits in ULTRAKILL until it comes back on at Steve's spot. Client
 * thread.
 * <p>
 * Minecraft's perspective (first person, third person behind or in front) goes to ULTRAKILL, which draws its picture
 * from there: {"t":"persp","p":0|1|2,"d":blocks back}, on a change and for each new connection.
 * <p>
 * While one of ULTRAKILL's own menus wants a pointer ({"t":"pointer","on":true}, round 17: the Sandbox arm's spawn menu,
 * the alter menu, the cheats menu), Minecraft lets go of the mouse: its cursor shows over the window, which shows ULTRAKILL's
 * picture, and goes to ULTRAKILL as the pointer (MouseHandlerMixin), with the buttons, wheel and keys as before, and Esc
 * (which closes ULTRAKILL's menu). When the menu closes, Minecraft takes the mouse again.
 */
public final class Control {
	private static boolean v1Mode = true;
	private static int announcedGeneration = -1;
	private static int viewWidth;
	private static int viewHeight;
	private static boolean inputWasOurs;
	private static int sentPerspective = -1;
	private static float sentDistance = -1f;
	/** ULTRAKILL said one of its menus wants a pointer (link thread), and Minecraft's cursor is it now (client thread). */
	private static volatile boolean pointerWanted;
	private static boolean pointerShown;
	/** ULTRAKILL's pause menu is open (round 25: the settings menu opens ULTRAKILL's options there): Minecraft pauses too. */
	private static volatile boolean ultrakillPaused;

	private Control() {
	}

	public static boolean v1Mode() {
		return v1Mode;
	}

	/** V1 moves the player and the camera: linked, in a world, V1 mode on, and ULTRAKILL sending its camera. */
	public static boolean driving() {
		Minecraft minecraft = Minecraft.getInstance();
		UkLink link = UkLink.get();
		return v1Mode && link != null && link.connected() && minecraft.level != null && minecraft.player != null && V1State.live() != null;
	}

	/**
	 * The keyboard and mouse go to ULTRAKILL: driving, no screen (chat, inventory, pause) open, window focused and
	 * the mouse captured, or Minecraft's cursor being the pointer for one of ULTRAKILL's menus. Until Minecraft has the
	 * mouse again (after Alt-Tab) the click that recaptures it is its own.
	 */
	public static boolean inputToUltrakill() {
		Minecraft minecraft = Minecraft.getInstance();
		return driving() && minecraft.gui.screen() == null && minecraft.isWindowActive() && (minecraft.mouseHandler.isMouseGrabbed() || pointerShown);
	}

	/** Minecraft's cursor is the pointer for one of ULTRAKILL's menus. */
	public static boolean pointer() {
		return pointerShown;
	}

	/** From the link thread: {"t":"pointer","on":bool,"paused":bool}. */
	static void onPointer(final JsonObject m) {
		pointerWanted = m.get("on").getAsBoolean();
		ultrakillPaused = m.has("paused") && m.get("paused").getAsBoolean();
	}

	/** Development (the dev demo, whose window never shows the pointer): ULTRAKILL said it's paused. */
	static boolean ultrakillSaysPaused() {
		return ultrakillPaused;
	}

	/** ULTRAKILL's pause menu is up over Minecraft's picture: Minecraft's world pauses with it (GuiMixin), so nothing hurts V1 meanwhile. */
	public static boolean ultrakillPaused() {
		return ultrakillPaused && pointerShown && driving();
	}

	/** Every client tick: the mouse let go while ULTRAKILL's menu wants a pointer (again, if a screen took it meanwhile), taken back after. */
	private static void followPointer(final Minecraft minecraft, final boolean driving) {
		boolean want = pointerWanted && driving && minecraft.gui.screen() == null && minecraft.isWindowActive();
		if (want) {
			if (!pointerShown || minecraft.mouseHandler.isMouseGrabbed()) {
				minecraft.mouseHandler.releaseMouse();
			}

			if (!pointerShown) {
				pointerShown = true;
				UKPassthrough.LOG.info("an ULTRAKILL menu wants the pointer: Minecraft's cursor is it");
			}
		} else if (pointerShown) {
			pointerShown = false;
			if (driving && minecraft.gui.screen() == null && minecraft.isWindowActive()) {
				minecraft.mouseHandler.grabMouse();
			}

			UKPassthrough.LOG.info("the ULTRAKILL menu closed: Minecraft has the mouse again");
		}
	}

	/** A key event from Minecraft's keyboard handler; true when it was used here (Minecraft must ignore it). */
	public static boolean onKey(final KeyEvent event, final int action) {
		Minecraft minecraft = Minecraft.getInstance();
		int key = event.key();
		if (key == InputConstants.KEY_F7) {
			if (action == InputConstants.PRESS && minecraft.level != null && minecraft.gui.screen() == null && UkLink.get().connected()) {
				toggle();
				return true;
			}

			return false;
		}

		if (key == InputConstants.KEY_TAB && V1Inventory.onTab(minecraft, action)) {
			return true;
		}

		if (!inputToUltrakill() || reservedForMinecraft(key) || minecraft.options.keyTogglePerspective.matches(event)) {
			return false;
		}

		if (action != InputConstants.REPEAT) {
			InputForwarder.key(key, action == InputConstants.PRESS);
		}

		return true;
	}

	/** A mouse button bound to Minecraft's perspective key stays Minecraft's. */
	public static boolean perspectiveButton(final int button) {
		return Minecraft.getInstance().options.keyTogglePerspective.matches(InputConstants.Type.MOUSE.getOrCreate(button));
	}

	private static boolean reservedForMinecraft(final int key) {
		// while one of ULTRAKILL's menus has the pointer, Esc is ULTRAKILL's: it closes that menu
		return (key == InputConstants.KEY_ESCAPE && !pointerShown) || key == InputConstants.KEY_T || key == InputConstants.KEY_SLASH
			|| (key >= InputConstants.KEY_F1 && key <= InputConstants.KEY_F12);
	}

	private static void toggle() {
		v1Mode = !v1Mode;
		sendMode();
		UkLink.chat(v1Mode ? "§aV1 mode on§r: ULTRAKILL has the controls (F7 for Steve)" : "§6V1 mode off§r: you're Steve (F7 for V1)");
	}

	/**
	 * Minecraft's perspective and how far back its camera goes (the player's scale times its camera distance attribute,
	 * 4 blocks normally), as Camera.alignWithEntity works it out.
	 */
	private static void sendPerspective(final Minecraft minecraft, final UkLink link) {
		CameraType type = minecraft.options.getCameraType();
		int perspective = type.isFirstPerson() ? 0 : type.isMirrored() ? 2 : 1;
		float distance = 4f;
		if (minecraft.player != null) {
			distance = minecraft.player.getScale() * (float) minecraft.player.getAttributeValue(Attributes.CAMERA_DISTANCE);
		}

		if (!link.connected() || (perspective == sentPerspective && Math.abs(distance - sentDistance) < 0.01f)) {
			return;
		}

		sentPerspective = perspective;
		sentDistance = distance;
		JsonObject m = UkLink.type("persp");
		m.addProperty("p", perspective);
		m.addProperty("d", distance);
		link.send(m);
	}

	private static void sendMode() {
		JsonObject m = UkLink.type("mode");
		m.addProperty("v1", v1Mode);
		UkLink.get().send(m);
	}

	/** Every client tick. */
	static void tick(final Minecraft minecraft) {
		UkLink link = UkLink.get();
		int w = minecraft.getWindow().getWidth();
		int h = minecraft.getWindow().getHeight();
		if (link.connected() && link.generation() != announcedGeneration) {
			sentPerspective = -1;
		}

		if (link.connected() && (link.generation() != announcedGeneration || w != viewWidth || h != viewHeight)) {
			announcedGeneration = link.generation();
			viewWidth = w;
			viewHeight = h;
			sendMode();
			// ULTRAKILL renders its picture at the same shape (the field of view only matches at the same aspect)
			JsonObject view = UkLink.type("view");
			view.addProperty("w", w);
			view.addProperty("h", h);
			link.send(view);
		}

		sendPerspective(minecraft, link);
		boolean driving = driving();
		UKPassthrough.v1Driving = driving;
		if (!link.connected()) {
			pointerWanted = false;
			ultrakillPaused = false;
		}

		followPointer(minecraft, driving);
		boolean ours = inputToUltrakill();
		if (ours && !inputWasOurs) {
			// whatever Minecraft thought was held (W from walking as Steve) is let go: V1 has the keys now
			KeyMapping.releaseAll();
		} else if (!ours && inputWasOurs) {
			InputForwarder.reset();
		}

		inputWasOurs = ours;
		if (ours) {
			InputForwarder.flush();
		}
	}
}
