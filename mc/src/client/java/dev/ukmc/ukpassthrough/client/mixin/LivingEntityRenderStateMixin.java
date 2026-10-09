package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.Oil;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Room for how oiled the mob is (see Oil). */
@Mixin(LivingEntityRenderState.class)
abstract class LivingEntityRenderStateMixin implements Oil.Tinted {
	@Unique
	private float ukpassthrough$oil;

	@Override
	public float ukpassthrough$oil() {
		return this.ukpassthrough$oil;
	}

	@Override
	public void ukpassthrough$setOil(final float amount) {
		this.ukpassthrough$oil = amount;
	}
}
