package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.jet.JetStream;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Jet stream speeds are far past vanilla's elytra speed check; skip it for gliders in the cubic world, who also keep jet speed for a while
 * after leaving the wind. Since 26.x the check (with its elytra game rule) is one method that takes whether the player is gliding.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class CubicJetMoveCheckMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "shouldCheckPlayerMovement", at = @At("HEAD"), cancellable = true)
    private void earthplatform$jetSpeed(boolean fallFlying, CallbackInfoReturnable<Boolean> result) {
        if (fallFlying && JetStream.inCubic(player)) result.setReturnValue(false);
    }
}
