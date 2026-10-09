package dev.ukmc.ukpassthrough.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ukmc.ukpassthrough.client.Control;
import dev.ukmc.ukpassthrough.client.InputForwarder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mouse buttons, the wheel and mouse movement go to ULTRAKILL while V1 has the controls. Movement is taken where
 * Minecraft would turn the player with it: the raw counts it accumulated this frame (the caller zeroes them after).
 * Buttons and the wheel still count as someone playing, for Minecraft's lower frame rate when nobody does (movement
 * already counts before this). While one of ULTRAKILL's menus has the pointer (Control.pointer: Minecraft has let go of the
 * mouse), where the cursor is goes instead, once a frame.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Shadow private double accumulatedDX;
	@Shadow private double accumulatedDY;
	@Shadow private double xpos;
	@Shadow private double ypos;

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$button(final long window, final MouseButtonInfo info, final int action, final CallbackInfo ci) {
		if (Control.inputToUltrakill() && !Control.perspectiveButton(info.button())) {
			InputForwarder.button(info.button(), action == InputConstants.PRESS);
			Minecraft.getInstance().getFramerateLimitTracker().onInputReceived();
			ci.cancel();
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$scroll(final long window, final double x, final double y, final CallbackInfo ci) {
		if (Control.inputToUltrakill()) {
			InputForwarder.wheel(y);
			Minecraft.getInstance().getFramerateLimitTracker().onInputReceived();
			ci.cancel();
		}
	}

	// the mouse isn't grabbed then, so turnPlayer doesn't come: once a frame here, after the movement is handled
	@Inject(method = "handleAccumulatedMovement", at = @At("TAIL"))
	private void ukpassthrough$pointer(final CallbackInfo ci) {
		if (Control.pointer() && Control.inputToUltrakill()) {
			com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
			InputForwarder.pointer(this.xpos / Math.max(1, window.getScreenWidth()), this.ypos / Math.max(1, window.getScreenHeight()));
			InputForwarder.flush();
		}
	}

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$look(final double movementTime, final CallbackInfo ci) {
		if (Control.inputToUltrakill()) {
			InputForwarder.mouse(this.accumulatedDX, this.accumulatedDY);
			// once a frame: everything since the last frame goes out together
			InputForwarder.flush();
			ci.cancel();
		}
	}
}
