package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.earthplatform.minecraft.cubic.jet.JetStream;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Another player's glider, or the recorded player in a Replay Mod replay, eases toward each position the
 * server sends over three ticks, so it is drawn about three and a half ticks behind its real path, and
 * further behind while it speeds up or turns. At jet speed that is some 200 blocks, so a replay camera
 * written from the recorded path never caught it. In the cubic world a glider eases only over the ticks
 * between its updates: it moves as smoothly and lags by a short, steady time (in a replay, 1.5 ticks).
 * Since 26.x an entity eases through its interpolation handler, which takes its steps from a field.
 */
@Mixin(InterpolationHandler.class)
abstract class CubicJetLerpMixin {
    @Shadow @Final private Entity entity;
    @Unique private int earthplatform$lastUpdate;
    /** The steps this update eases over, or -1 for the handler's own. */
    @Unique private int earthplatform$steps = -1;

    @Inject(method = "interpolateTo", at = @At("HEAD"))
    private void earthplatform$keepUp(CallbackInfo info) {
        earthplatform$steps = -1;
        if (!(entity instanceof LivingEntity self)) return;
        int since = self.tickCount - earthplatform$lastUpdate;
        earthplatform$lastUpdate = self.tickCount;
        if (JetStream.remoteGlider(self)) earthplatform$steps = since;
    }

    @ModifyExpressionValue(method = "interpolateTo", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/InterpolationHandler;interpolationSteps:I"))
    private int earthplatform$steps(int steps) {
        return earthplatform$steps < 0 ? steps : JetStream.easeSteps(steps, earthplatform$steps);
    }
}
