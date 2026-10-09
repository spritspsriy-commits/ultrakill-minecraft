package dev.ukmc.ukpassthrough.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.ukmc.ukpassthrough.client.Control;
import dev.ukmc.ukpassthrough.client.StainMask;
import dev.ukmc.ukpassthrough.client.V1Hand;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While V1 drives: no Steve hand (V1's arms come from ULTRAKILL; an item V1 holds that isn't a block is Minecraft's, drawn
 * on the left), and none of Minecraft's own view movement on top of
 * V1's camera: the hurt tilt (Steve's damage event still arrives, though V1 takes the damage), the walking bob, and the
 * nausea and portal wobble. ULTRAKILL's picture can't follow them, so against a world that tilts on its own, the blood
 * and shots in the picture looked stuck to the screen. ULTRAKILL's own shake and tilt come through V1's camera.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$noHand(final CameraRenderState camera, final PlayerRenderState player, final GpuTextureView depth, final CallbackInfo ci) {
		if (!Control.driving()) {
			return;
		}

		if (V1Hand.drawsItem() && player.avatarRenderState != null) {
			// V1 holds an item (not a block) in its blue hand, its left: Steve's main hand item, drawn on that side alone, as
			// Minecraft draws an offhand item (swings, eating and drawing a bow included)
			player.avatarRenderState.mainArm = HumanoidArm.LEFT;
			player.firstPersonHandsAndItems.handRenderSelection = FirstPersonHandsAndItemsRenderState.HandRenderSelection.RENDER_MAIN_HAND_ONLY;
			return;
		}

		ci.cancel();
	}

	// the item V1 holds is drawn: keep the blood off it (StainMask)
	@Inject(method = "renderItemInHand", at = @At("RETURN"))
	private void ukpassthrough$heldItemDrawn(final CameraRenderState camera, final PlayerRenderState player, final GpuTextureView depth, final CallbackInfo ci) {
		if (Control.driving() && V1Hand.drawsItem()) {
			StainMask.heldItemDrawn(depth);
		}
	}

	@Inject(method = {"bobHurt", "bobView"}, at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$noBob(final CameraRenderState camera, final PoseStack poseStack, final CallbackInfo ci) {
		if (Control.driving()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void ukpassthrough$noWobble(final CallbackInfo ci) {
		if (Control.driving()) {
			// only the world's projection reads these; the purple portal overlay is the HUD's and reads the player's own
			PlayerRenderState player = ((GameRenderer) (Object) this).gameRenderState().levelRenderState.playerRenderState;
			player.portalEffectIntensity = 0.0F;
			player.nauseaEffectIntensity = 0.0F;
		}
	}
}
