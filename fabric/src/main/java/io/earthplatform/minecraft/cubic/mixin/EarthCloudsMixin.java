package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.play.client.EarthClouds;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws Earth's raised deck when no other renderer owns the clouds. Create Sky at priority 900
 * draws first; this fallback runs afterward, but still before Sodium at priority 1000. Since 26.x
 * the level's cloud pass calls the cloud renderer, with the frame's camera state.
 */
@Mixin(value = CloudRenderer.class, priority = 950)
abstract class EarthCloudsMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void earthplatform$earthClouds(int color, CloudStatus status, float cloudHeight, int range, Vec3 camera, long gameTime, float partialTick,
                                           CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        CameraRenderState state = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (state == null || state.viewRotationMatrix == null || state.projectionMatrix == null) return;
        if (EarthClouds.render(minecraft.level, state.viewRotationMatrix, state.projectionMatrix, partialTick, camera.x, camera.y, camera.z)) ci.cancel();
    }
}
