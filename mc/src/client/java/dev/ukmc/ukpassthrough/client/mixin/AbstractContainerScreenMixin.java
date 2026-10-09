package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.InventoryClicks;
import dev.ukmc.ukpassthrough.client.V1Inventory;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The inventory while V1 drives: a click puts an item in V1's hand, a drag moves it, and off the window throws it (V1Inventory). */
@Mixin(AbstractContainerScreen.class)
abstract class AbstractContainerScreenMixin implements InventoryClicks {
	@Shadow
	protected int leftPos;

	@Shadow
	protected int topPos;

	@Shadow
	protected abstract void slotClicked(Slot slot, int slotId, int button, ContainerInput input);

	@Shadow
	protected abstract boolean hasClickedOutside(double x, double y, int left, int top);

	@Shadow
	private Slot getHoveredSlot(final double x, final double y) {
		throw new AssertionError();
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"))
	private void ukpassthrough$press(final MouseButtonEvent event, final boolean doubleClick, final CallbackInfoReturnable<Boolean> cir) {
		V1Inventory.onPress((AbstractContainerScreen<?>) (Object) this, event, this.getHoveredSlot(event.x(), event.y()));
	}

	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$drag(final MouseButtonEvent event, final double dx, final double dy, final CallbackInfoReturnable<Boolean> cir) {
		if (V1Inventory.dragging()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$release(final MouseButtonEvent event, final CallbackInfoReturnable<Boolean> cir) {
		if (V1Inventory.onRelease((AbstractContainerScreen<?>) (Object) this, this, event, this.getHoveredSlot(event.x(), event.y()))) {
			cir.setReturnValue(true);
		}
	}

	@Override
	public void ukpassthrough$click(final Slot slot, final int button, final ContainerInput input) {
		this.slotClicked(slot, slot.index, button, input);
	}

	@Override
	public boolean ukpassthrough$outside(final double x, final double y) {
		// the screen's own (the creative inventory's remembers it for its next click on no slot)
		return this.hasClickedOutside(x, y, this.leftPos, this.topPos);
	}

	@Override
	public void ukpassthrough$throwCarried() {
		// as the screen's own click outside its window: no slot, the id -999
		this.slotClicked(null, -999, 0, ContainerInput.PICKUP);
	}
}
