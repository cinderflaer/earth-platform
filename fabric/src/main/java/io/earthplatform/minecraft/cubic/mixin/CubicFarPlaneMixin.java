package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.client.FarRenderer;
import net.minecraft.client.Camera;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Far terrain reaches kilometres past the render distance, so the cubic world's projection keeps
 * it inside the far clip plane. Since 26.x the camera works out its depth-far once a frame, and
 * the projection, the culling frustum and the frame's camera state all take it from there.
 */
@Mixin(Camera.class)
abstract class CubicFarPlaneMixin {
    @Shadow private float depthFar;

    @Inject(method = "update", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/Camera;depthFar:F"))
    private void earthplatform$farTerrainDepth(CallbackInfo info) {
        if (ClientCubeWorld.current() != null) depthFar = Math.max(depthFar, FarRenderer.depthFar());
    }
}
