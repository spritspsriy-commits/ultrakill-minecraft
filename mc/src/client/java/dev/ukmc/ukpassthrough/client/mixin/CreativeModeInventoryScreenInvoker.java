package dev.ukmc.ukpassthrough.client.mixin;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Demo 17 opens the creative inventory's inventory tab, where it draws the player (DevDemo). */
@Mixin(CreativeModeInventoryScreen.class)
public interface CreativeModeInventoryScreenInvoker {
	@Invoker("selectTab")
	void ukpassthrough$selectTab(CreativeModeTab tab);
}
