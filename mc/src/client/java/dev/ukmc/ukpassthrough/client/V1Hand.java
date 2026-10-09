package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.ukmc.ukpassthrough.UKPassthrough;
import dev.ukmc.ukpassthrough.client.mixin.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What V1's blue hand holds (round 14; uk/src/Hand.cs on the other side). An item clicked in the inventory ({@link V1Inventory})
 * goes into Steve's main hand for real: the hotbar slot Steve has selected. One from the rows above the hotbar moves down into
 * it first, to the slot Minecraft's pick-block would use (an empty one if there is one). So Minecraft's own code places it,
 * swings it, mines with it and uses it, with its own rules (stacks run down, tools wear, cooldowns), and V1 holds it in
 * ULTRAKILL:
 * <ul>
 * <li>a block (an item drawn as a 3D block in the inventory) the Feedbacker holds as ULTRAKILL holds a skull:
 * {"t":"held","k":"block","id":n,"m":{...}} carries its look ({@link HeldModel});</li>
 * <li>anything else Minecraft draws on the left of the screen, where its offhand item would be ({@link #drawsItem}), and
 * ULTRAKILL hides the blue arm ({"t":"held","k":"item","id":n}).</li>
 * </ul>
 * While V1 holds something ULTRAKILL's punch key for the Feedbacker (F on this PC) doesn't punch: {"t":"hand","d":true|false}
 * comes for it going down and up, and holds Minecraft's own attack key (swords, axes, pickaxes, shovels, spears and the mace:
 * they hit and mine) or its use key (everything else: blocks are placed, food eaten, a bow drawn) down for as long, so holding
 * it keeps mining, eating or placing as holding the mouse button does for Steve. When Steve's arm swings with a block, V1's
 * blue arm jabs ({"t":"swing"}).
 * <p>
 * V1 lets go ({"t":"held","k":"none"}) when the stack is used up, when the item it holds is moved in the inventory, and when
 * F7 gives Steve the controls; ULTRAKILL lets go itself on a Knuckleblaster punch or an arm change ({"t":"letgo","id":n}).
 * Client thread, except the link handlers.
 */
public final class V1Hand {
	private static boolean held;
	/** The hotbar slot V1's item is in. */
	private static int slot;
	/** Counts what V1 has held, so a let-go from ULTRAKILL for something earlier is ignored. */
	private static int holdId;
	/** What ULTRAKILL was told V1 holds (EMPTY: not told yet). */
	private static ItemStack shown = ItemStack.EMPTY;
	private static boolean block;
	/** The item hits and mines (Minecraft's attack key) instead of being used (its use key). */
	private static boolean hits;
	/** The key held down for V1's punch key, or null. */
	private static KeyMapping down;
	private static int announcedGeneration = -1;
	private static boolean wasSwinging;

	private V1Hand() {
	}

	/** Until when (System.nanoTime) the blue arm is out punching and the held item isn't drawn. */
	private static volatile long armOutUntil;

	/** Minecraft draws the held item in V1's hand (it isn't a block, which ULTRAKILL draws), in first person; not while the arm punches. */
	public static boolean drawsItem() {
		return held && !block && !shown.isEmpty() && Control.driving() && System.nanoTime() >= armOutUntil;
	}

	/**
	 * From the link thread: {"t":"armout","ms":n}: the blue arm comes out to punch (a parry or a projectile boost with something in
	 * V1's hand, round 20b), so the item isn't drawn meanwhile (ULTRAKILL draws the arm over where it is).
	 */
	static void onArmOut(final JsonObject m) {
		armOutUntil = System.nanoTime() + (m.has("ms") ? m.get("ms").getAsLong() : 350L) * 1_000_000L;
	}

	/** The item at {@code index} in the player's inventory into V1's hand. */
	static void take(final Minecraft minecraft, final int index) {
		LocalPlayer player = minecraft.player;
		Inventory inventory = player.getInventory();
		int hotbar = index;
		if (!Inventory.isHotbarSlot(index)) {
			hotbar = inventory.getSuitableHotbarSlot();
			// as a number key over that slot in the inventory: the item goes down to the hotbar, what was there goes up
			Slot from = menuSlot(player.inventoryMenu, inventory, index);
			if (from == null) {
				return;
			}

			minecraft.gameMode.handleContainerInput(player.inventoryMenu.containerId, from.index, hotbar, ContainerInput.SWAP, player);
		}

		hold(minecraft, hotbar);
	}

	/** Creative: a new stack (from the creative inventory's tabs) into V1's hand, put in the hotbar as pick-block would. */
	static void takeNew(final Minecraft minecraft, final ItemStack stack) {
		Inventory inventory = minecraft.player.getInventory();
		int hotbar = inventory.getSuitableHotbarSlot();
		inventory.setItem(hotbar, stack);
		minecraft.gameMode.handleCreativeModeItemAdd(stack, InventoryMenu.USE_ROW_SLOT_START + hotbar);
		hold(minecraft, hotbar);
	}

	private static Slot menuSlot(final InventoryMenu menu, final Inventory inventory, final int index) {
		for (Slot s : menu.slots) {
			if (s.container == inventory && s.getContainerSlot() == index) {
				return s;
			}
		}

		return null;
	}

	private static void hold(final Minecraft minecraft, final int hotbar) {
		release();
		// Minecraft sends the selected slot to the server itself before its next action
		minecraft.player.getInventory().setSelectedSlot(hotbar);
		held = true;
		slot = hotbar;
		holdId++;
		shown = ItemStack.EMPTY;
	}

	/** Every client tick. */
	static void tick(final Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		UkLink link = UkLink.get();
		if (!held) {
			return;
		}

		if (player == null || !link.connected()) {
			letGo("the link or the world is gone", false);
			return;
		}

		if (!Control.v1Mode()) {
			letGo("Steve has the controls", true);
			return;
		}

		Inventory inventory = player.getInventory();
		ItemStack now = inventory.getItem(slot);
		if (inventory.getSelectedSlot() != slot) {
			letGo("another hotbar slot was selected", true);
			return;
		}

		if (!shown.isEmpty() && (now.isEmpty() || !ItemStack.isSameItem(now, shown)) && minecraft.gui.screen() != null) {
			letGo("it was moved in the inventory", true);
			return;
		}

		if (now.isEmpty()) {
			letGo("the stack is used up", true);
			return;
		}

		if (link.generation() != announcedGeneration || !ItemStack.isSameItem(now, shown)) {
			// the first time, for a new ULTRAKILL, or after using it made it something else (a bucket filled)
			announce(minecraft, now);
		}

		boolean swinging = player.isSwinging();
		if (block && swinging && !wasSwinging) {
			link.send(UkLink.type("swing"));
		}

		wasSwinging = swinging;
	}

	private static void announce(final Minecraft minecraft, final ItemStack stack) {
		UkLink link = UkLink.get();
		announcedGeneration = link.generation();
		ItemStackRenderState look = HeldModel.resolve(minecraft, stack);
		block = HeldModel.isBlock(look);
		hits = stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.SPEARS)
			|| stack.is(Items.MACE);
		JsonObject m = UkLink.type("held");
		m.addProperty("k", block ? "block" : "item");
		m.addProperty("id", holdId);
		m.addProperty("name", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		m.addProperty("hits", hits);
		if (block) {
			m.add("m", HeldModel.write(look));
		}

		link.send(m);
		shown = stack.copy();
		wasSwinging = false;
		UKPassthrough.LOG.info("V1 holds {} in its blue hand ({}; its punch key {})", V1Inventory.describe(stack), block ? "a block, drawn by ULTRAKILL" : "drawn by Minecraft",
			hits ? "hits and mines" : "uses it");
	}

	/** V1's hand is empty again; {@code tell}: ULTRAKILL still thinks it holds something. */
	private static void letGo(final String why, final boolean tell) {
		release();
		held = false;
		if (tell && !shown.isEmpty()) {
			JsonObject m = UkLink.type("held");
			m.addProperty("k", "none");
			m.addProperty("id", holdId);
			UkLink.get().send(m);
		}

		UKPassthrough.LOG.info("V1's blue hand is empty again: {}", why);
		shown = ItemStack.EMPTY;
	}

	private static void release() {
		if (down != null) {
			down.setDown(false);
			down = null;
		}
	}

	/** From the link thread: {"t":"hand","d":bool}, V1's punch key down or up while it holds something. */
	static void onHand(final JsonObject m) {
		boolean d = m.get("d").getAsBoolean();
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> press(minecraft, d));
	}

	private static void press(final Minecraft minecraft, final boolean d) {
		if (!d || !held || !Control.driving() || minecraft.gui.screen() != null) {
			release();
			return;
		}

		KeyMapping key = hits ? minecraft.options.keyAttack : minecraft.options.keyUse;
		InputConstants.Key bound = ((KeyMappingAccessor) key).ukpassthrough$key();
		release();
		// as the mouse button does: down for as long as it's held, and one click for Minecraft's next tick to act on
		KeyMapping.set(bound, true);
		KeyMapping.click(bound);
		down = key;
	}

	/** From the link thread: {"t":"letgo","id":n}, ULTRAKILL emptied V1's hand (a Knuckleblaster punch, an arm change). */
	static void onLetGo(final JsonObject m) {
		int id = m.has("id") ? m.get("id").getAsInt() : -1;
		Minecraft.getInstance().execute(() -> {
			if (held && id == holdId) {
				letGo("ULTRAKILL let go (a Knuckleblaster punch or an arm change)", false);
			}
		});
	}
}
