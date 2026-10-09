package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Collects the keys, mouse buttons, wheel and mouse movement meant for ULTRAKILL and sends them once a frame:
 * {"t":"in","k":[scancode,down, ...],"b":[button,down, ...],"w":wheel,"m":[dx,dy],"p":[x,y]}, or {"t":"in","reset":true} to
 * release everything. Keys are SDL scancodes (Minecraft's InputConstants.KEY_*), buttons SDL buttons
 * (1 left, 2 middle, 3 right, 4/5 side), mouse movement raw counts (y down), and while one of ULTRAKILL's menus wants a
 * pointer, where the cursor is on the window (0 to 1 from the top left, Control.pointer). Client thread only.
 */
public final class InputForwarder {
	private static final IntArrayList keys = new IntArrayList();
	private static final IntArrayList buttons = new IntArrayList();
	private static final IntOpenHashSet heldKeys = new IntOpenHashSet();
	private static final IntOpenHashSet heldButtons = new IntOpenHashSet();
	private static double wheel;
	private static double dx;
	private static double dy;
	/** The pointer to send (negative: none), and the last one sent. */
	private static double px = -1.0;
	private static double py = -1.0;
	private static double sentX = -1.0;
	private static double sentY = -1.0;

	private InputForwarder() {
	}

	public static void key(final int scancode, final boolean down) {
		// a release for a key ULTRAKILL never saw go down (pressed before V1 had the input) isn't news
		if (down ? heldKeys.add(scancode) : heldKeys.remove(scancode)) {
			keys.add(scancode);
			keys.add(down ? 1 : 0);
		}
	}

	public static void button(final int button, final boolean down) {
		if (down ? heldButtons.add(button) : heldButtons.remove(button)) {
			buttons.add(button);
			buttons.add(down ? 1 : 0);
		}
	}

	public static void wheel(final double y) {
		wheel += y;
	}

	public static void mouse(final double x, final double y) {
		dx += x;
		dy += y;
	}

	/** Where the cursor is on the window (0 to 1 from the top left), for the pointer of ULTRAKILL's menus; sent when it moved. */
	public static void pointer(final double x, final double y) {
		if (Math.abs(x - sentX) > 1.0E-4 || Math.abs(y - sentY) > 1.0E-4) {
			px = Math.max(0.0, Math.min(1.0, x));
			py = Math.max(0.0, Math.min(1.0, y));
		}
	}

	/** Sends what collected since the last frame, if anything. */
	public static void flush() {
		if (keys.isEmpty() && buttons.isEmpty() && wheel == 0.0 && dx == 0.0 && dy == 0.0 && px < 0.0) {
			return;
		}

		JsonObject m = UkLink.type("in");
		if (!keys.isEmpty()) {
			m.add("k", ints(keys));
		}

		if (!buttons.isEmpty()) {
			m.add("b", ints(buttons));
		}

		if (wheel != 0.0) {
			m.addProperty("w", wheel);
		}

		if (dx != 0.0 || dy != 0.0) {
			JsonArray d = new JsonArray();
			d.add(dx);
			d.add(dy);
			m.add("m", d);
		}

		if (px >= 0.0) {
			JsonArray p = new JsonArray();
			p.add(px);
			p.add(py);
			m.add("p", p);
			sentX = px;
			sentY = py;
			px = -1.0;
		}

		UkLink.get().send(m);
		clearPending();
	}

	/** Lets go of everything in ULTRAKILL (input moves back to Minecraft). */
	public static void reset() {
		clearPending();
		heldKeys.clear();
		heldButtons.clear();
		JsonObject m = UkLink.type("in");
		m.addProperty("reset", true);
		UkLink.get().send(m);
	}

	private static void clearPending() {
		keys.clear();
		buttons.clear();
		wheel = 0.0;
		dx = 0.0;
		dy = 0.0;
	}

	private static JsonArray ints(final IntArrayList list) {
		JsonArray a = new JsonArray();
		for (int i = 0; i < list.size(); i++) {
			a.add(list.getInt(i));
		}

		return a;
	}
}
