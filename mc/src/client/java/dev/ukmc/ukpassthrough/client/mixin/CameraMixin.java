package dev.ukmc.ukpassthrough.client.mixin;

import dev.ukmc.ukpassthrough.client.Control;
import dev.ukmc.ukpassthrough.client.V1State;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While V1 drives, Minecraft renders from V1's camera: its position, rotation (with roll) and field of view. In third
 * person that's the camera ULTRAKILL drew its picture from, behind or in front of V1; the camera stays "attached", so
 * Minecraft doesn't draw Steve there (V1's body is in ULTRAKILL's picture) or move the camera back a second time.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
	private static final float DEG = (float) (Math.PI / 180.0);
	@Shadow @Final private static Vector3fc FORWARDS;
	@Shadow @Final private static Vector3fc UP;
	@Shadow @Final private static Vector3fc LEFT;
	@Shadow @Final private Vector3f forwards;
	@Shadow @Final private Vector3f up;
	@Shadow @Final private Vector3f left;
	@Shadow @Final private Quaternionf rotation;
	@Shadow private float xRot;
	@Shadow private float yRot;
	@Shadow private boolean detached;
	@Shadow private int matrixPropertiesDirty;

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void ukpassthrough$v1Camera(final float partialTicks, final CallbackInfo ci) {
		V1State.Pose p = Control.driving() ? V1State.live() : null;
		if (p == null) {
			return;
		}

		this.xRot = p.cpitch();
		this.yRot = p.cyaw();
		// what Camera.setRotation does, plus roll
		this.rotation.rotationYXZ((float) Math.PI - p.cyaw() * DEG, -p.cpitch() * DEG, p.croll() * DEG);
		FORWARDS.rotate(this.rotation, this.forwards);
		UP.rotate(this.rotation, this.up);
		LEFT.rotate(this.rotation, this.left);
		this.matrixPropertiesDirty |= 3;
		this.setPosition(p.cx(), p.cy(), p.cz());
		this.detached = false;
		float[] c = p.check();
		if (c != null) {
			// development: how far this camera is off the one ULTRAKILL drew from
			V1State.noteCameraError(angle(this.forwards, c[0], c[1], c[2]), angle(this.up, c[3], c[4], c[5]));
		}
	}

	private static float angle(final Vector3f a, final float x, final float y, final float z) {
		Vector3f b = new Vector3f(x, y, z);
		return b.lengthSquared() < 1.0E-8F ? 0.0F : (float) Math.toDegrees(a.angle(b.normalize()));
	}

	@Inject(method = "calculateFov", at = @At("HEAD"), cancellable = true)
	private void ukpassthrough$v1Fov(final float partialTicks, final CallbackInfoReturnable<Float> cir) {
		V1State.Pose p = Control.driving() ? V1State.live() : null;
		if (p != null) {
			cir.setReturnValue(p.fov());
		}
	}
}
