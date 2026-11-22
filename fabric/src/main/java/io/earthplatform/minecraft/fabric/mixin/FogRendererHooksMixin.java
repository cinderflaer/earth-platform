package io.earthplatform.minecraft.fabric.mixin;

import io.earthplatform.minecraft.cubic.client.CubicClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge's fog events (ViewportEvent.ComputeFogColor and RenderFog). Since 26.x the fog's colour
 * and distances are worked out once a frame into a {@link FogData}, which the sky, the clear colour
 * and every shader then read, so both change it as it is returned.
 */
@Mixin(FogRenderer.class)
abstract class FogRendererHooksMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void earthplatform$fog(Camera camera, int renderDistance, DeltaTracker delta, float darken, ClientLevel level,
                                   CallbackInfoReturnable<FogData> result) {
        FogData data = result.getReturnValue();
        // The deep sea darkens: the final fog colour.
        float light = CubicClient.waterFogLight(camera);
        if (light != 1.0F) data.color.mul(light, light, light, 1.0F);
        // Terrain fog out at the far terrain's reach.
        float[] fog = CubicClient.fog(FogRenderer.FogMode.WORLD, camera.getFluidInCamera(), camera, data.renderDistanceEnd);
        if (fog == null) return;
        data.renderDistanceStart = fog[0];
        data.renderDistanceEnd = fog[1];
    }
}
