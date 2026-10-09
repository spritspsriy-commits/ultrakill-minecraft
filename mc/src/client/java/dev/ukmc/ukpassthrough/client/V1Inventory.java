package dev.ukmc.ukpassthrough.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ukmc.ukpassthrough.UKPassthrough;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Minecraft's inventory while V1 drives (round 14). Tab opens it and closes it again (Tab is no longer ULTRAKILL's stats
 * key while V1 plays). In it:
 * <ul>
 * <li>a click on an item (pressed and let go on the same slot) closes the inventory and puts that item in V1's hand
 * ({@link V1Hand}); in creative, an item from the tabs comes as a full stack;</li>
 * <li>an item is moved by holding the button and dragging it to another slot: it goes there (or joins a stack of the same,
 * or swaps with what's there, which goes back to where the dragged item came from); let go off every slot, it goes back;</li>
 * <li>let go outside the inventory's window, the whole stack is thrown, as Minecraft throws what's on the cursor when the window's
 * outside is clicked (round 29).</li>
 * </ul>
 * The press is still Minecraft's own click (it picks the item up, so the item follows the cursor while it's dragged); only
 * what the release does is new. Minecraft's other ways stay: shift-clicks, number keys over a slot, right clicks, Q, and
 * anything already on the cursor (a right click's half stack, a crafted item) is put down as Minecraft does. Clicks on the
 * armor, crafting and offhand slots don't fill V1's hand (dragging still moves those items). Client thread.
 */
public final class V1Inventory {
	/** The slot the left button went down on, while it's held. */
	private static Slot pressed;

	private V1Inventory() {
	}

	/** Tab while V1 drives; true when it was used (it goes neither to Minecraft nor to ULTRAKILL). */
	static boolean onTab(final Minecraft minecraft, final int action) {
		if (!Control.driving()) {
			return false;
		}

		Screen screen = minecraft.gui.screen();
		if (screen == null) {
			if (action == InputConstants.PRESS) {
				// as Minecraft's own inventory key does (in creative its screen opens the creative inventory)
				if (minecraft.gameMode.isServerControlledInventory()) {
					minecraft.player.sendOpenInventory();
				} else {
					minecraft.gui.setScreen(new InventoryScreen(minecraft.player));
				}
			}

			return true;
		}

		if (isInventory(screen)) {
			if (action == InputConstants.PRESS) {
				screen.onClose();
			}

			return true;
		}

		return false;
	}

	private static boolean isInventory(final Screen screen) {
		return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
	}

	/** Before the screen's own handling of a press. */
	public static void onPress(final AbstractContainerScreen<?> screen, final MouseButtonEvent event, final Slot slot) {
		pressed = null;
		if (Control.driving() && isInventory(screen) && event.button() == InputConstants.MOUSE_BUTTON_LEFT && !event.hasShiftDown() && slot != null
			&& slot.hasItem() && screen.getMenu().getCarried().isEmpty()) {
			pressed = slot;
		}
	}

	/** A drag that started on an item is this class's: the screen's own dragging (spreading a stack over slots) stays out of it. */
	public static boolean dragging() {
		return pressed != null;
	}

	/** Instead of the screen's own handling of a release, if true. */
	public static boolean onRelease(final AbstractContainerScreen<?> screen, final InventoryClicks clicks, final MouseButtonEvent event, final Slot target) {
		Slot from = pressed;
		if (from == null || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}

		pressed = null;
		if (!Control.driving() || !isInventory(screen)) {
			return false;
		}

		AbstractContainerMenu menu = screen.getMenu();
		Minecraft minecraft = Minecraft.getInstance();
		if (target == from) {
			ItemStack picked = menu.getCarried().copy();
			putBack(screen, clicks, from);
			select(minecraft, screen, from, picked);
			return true;
		}

		if (target != null) {
			String what = describe(menu.getCarried());
			clicks.ukpassthrough$click(target, 0, ContainerInput.PICKUP);
			// what was on that slot (or what didn't fit there) goes back to where the dragged item came from
			putBack(screen, clicks, from);
			UKPassthrough.LOG.info("V1's inventory: {} dragged from slot {} to slot {}{}", what, from.index, target.index,
				menu.getCarried().isEmpty() ? "" : ", something is left on the cursor");
			return true;
		}

		if (clicks.ukpassthrough$outside(event.x(), event.y()) && minecraft.player.canDropItems()) {
			// let go outside the window: thrown, as a click there throws what's on the cursor (round 29, the user's request)
			String what = describe(menu.getCarried());
			clicks.ukpassthrough$throwCarried();
			UKPassthrough.LOG.info("V1's inventory: {} dragged from slot {} out of the window: thrown{}", what, from.index,
				menu.getCarried().isEmpty() ? "" : ", something is left on the cursor");
			putBack(screen, clicks, from);
			return true;
		}

		putBack(screen, clicks, from);
		return true;
	}

	/** Whatever is on the cursor back on the slot it came from (the creative tabs' items never run out: those just go). */
	private static void putBack(final AbstractContainerScreen<?> screen, final InventoryClicks clicks, final Slot from) {
		AbstractContainerMenu menu = screen.getMenu();
		if (menu.getCarried().isEmpty()) {
			return;
		}

		if (creativeTab(screen, from)) {
			menu.setCarried(ItemStack.EMPTY);
			return;
		}

		clicks.ukpassthrough$click(from, 0, ContainerInput.PICKUP);
	}

	/** A click on {@code slot}: the item there into V1's hand, if it's one of the player's own (or from a creative tab). */
	private static void select(final Minecraft minecraft, final AbstractContainerScreen<?> screen, final Slot slot, final ItemStack picked) {
		Inventory inventory = minecraft.player.getInventory();
		int index = indexOf(inventory, slot.getItem());
		if (index >= 0 && (index < Inventory.INVENTORY_SIZE || index == Inventory.SLOT_OFFHAND)) {
			UKPassthrough.LOG.info("V1's inventory: {} clicked (inventory slot {}): into V1's hand", describe(slot.getItem()), index);
			screen.onClose();
			V1Hand.take(minecraft, index);
		} else if (creativeTab(screen, slot) && !picked.isEmpty()) {
			UKPassthrough.LOG.info("V1's inventory: {} clicked in a creative tab: a stack into V1's hand", describe(picked));
			screen.onClose();
			V1Hand.takeNew(minecraft, picked);
		} else {
			UKPassthrough.LOG.info("V1's inventory: {} clicked (not an inventory slot): nothing for V1's hand", describe(slot.getItem()));
		}
	}

	/**
	 * Where {@code stack} is in the player's inventory (the same stack, not an equal one), or -1. The creative inventory's own
	 * slots stand for the inventory's under other numbers, so the stack itself tells.
	 */
	private static int indexOf(final Inventory inventory, final ItemStack stack) {
		if (stack.isEmpty()) {
			return -1;
		}

		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i) == stack) {
				return i;
			}
		}

		return -1;
	}

	/** One of the creative inventory's own item slots (its tabs' items, not the player's). */
	private static boolean creativeTab(final AbstractContainerScreen<?> screen, final Slot slot) {
		return screen instanceof CreativeModeInventoryScreen && !(slot.container instanceof Inventory);
	}

	static String describe(final ItemStack stack) {
		return stack.isEmpty() ? "nothing" : stack.getCount() + " " + BuiltInRegistries.ITEM.getKey(stack.getItem());
	}
}
