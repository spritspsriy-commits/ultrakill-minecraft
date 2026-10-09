package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.Oil;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mobs with ULTRAKILL's gasoline on them look oiled (see Oil). Wolves and tropical fish have their own tint and stay as they are. */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
	private void ukpassthrough$oil(final LivingEntity entity, final LivingEntityRenderState state, final float partialTicks, final CallbackInfo ci) {
		((Oil.Tinted) state).ukpassthrough$setOil(Oil.amount(entity.getId()));
	}

	@Inject(method = "getModelTint", at = @At("RETURN"), cancellable = true)
	private void ukpassthrough$oiled(final LivingEntityRenderState state, final CallbackInfoReturnable<Integer> cir) {
		float oil = ((Oil.Tinted) state).ukpassthrough$oil();
		if (oil > 0.0F) {
			cir.setReturnValue(Oil.tint(cir.getReturnValueI(), oil));
		}
	}
}
