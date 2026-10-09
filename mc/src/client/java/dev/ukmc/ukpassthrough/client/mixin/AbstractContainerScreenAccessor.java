package dev.ukmc.ukpassthrough.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen's slots are on the screen, for the demo's clicks (DevDemo). */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int ukpassthrough$leftPos();

	@Accessor("topPos")
	int ukpassthrough$topPos();
}
