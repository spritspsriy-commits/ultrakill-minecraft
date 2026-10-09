package dev.ukmc.ukpassthrough.client;

import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

/** A container screen's own slot click (AbstractContainerScreenMixin), with whatever the screen adds to it (the creative inventory's). */
public interface InventoryClicks {
	void ukpassthrough$click(Slot slot, int button, ContainerInput input);

	/** Whether (x, y) is outside the screen's window, as the screen itself tells for a click (its tabs and recipe book count as inside). */
	boolean ukpassthrough$outside(double x, double y);

	/** What's on the cursor thrown out, as a left click outside the window throws it (the screen's own click on no slot). */
	void ukpassthrough$throwCarried();
}
