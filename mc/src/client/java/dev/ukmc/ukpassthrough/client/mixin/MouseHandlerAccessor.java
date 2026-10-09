package dev.ukmc.ukpassthrough.client.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The demo's dev client isn't the focused window, so Minecraft never grabs its mouse, and holding the attack key only keeps
 * mining with a grabbed mouse (Minecraft.handleKeybinds): the demo says it's grabbed while it mines (DevDemo). Demo 17 puts
 * the cursor where it wants it over the inventory (nobody moves the real one there).
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Accessor("mouseGrabbed")
	void ukpassthrough$setMouseGrabbed(boolean grabbed);

	@Accessor("xpos")
	void ukpassthrough$setXpos(double x);

	@Accessor("ypos")
	void ukpassthrough$setYpos(double y);
}
