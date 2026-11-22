package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla kicks a player who spends four seconds with no blocks around them, falling or not. Earth
 * has drops far longer than four seconds, and after a far teleport the client also holds the
 * player in mid-air until the cubes it stands in and on arrive (see CubicTravelMixin). In a cubic
 * level a player waiting on those cubes, or going down, is not floating.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class CubicFloatingKickMixin {
    @Shadow public ServerPlayer player;
    @Shadow private boolean clientIsFloating;
    @Unique private double earthplatform$lastY = Double.NaN;

    @Inject(method = "noBlocksAround", at = @At("HEAD"), cancellable = true)
    private void earthplatform$waitingForCubes(Entity entity, CallbackInfoReturnable<Boolean> result) {
        if (!(CubicLevel.of(entity.level()) instanceof ServerCubeWorld cubes)) return;
        int x = entity.getBlockX(), y = entity.getBlockY(), z = entity.getBlockZ();
        if (!cubes.sentTo(player, x, y, z) || !cubes.sentTo(player, x, y - 2, z)) result.setReturnValue(false);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void earthplatform$falling(CallbackInfo info) {
        double y = player.getY(), last = earthplatform$lastY;
        earthplatform$lastY = y;
        if (y < last - 0.05 && CubicLevel.of(player.level()) != null) clientIsFloating = false;
        // Held in the air of cubes still to come, a player has not fallen: nothing counts toward fall damage.
        if (CubicLevel.of(player.level()) instanceof ServerCubeWorld cubes && !player.isSpectator()
                && !io.earthplatform.minecraft.cubic.jet.JetStream.aboveTerrain(player)
                && (!cubes.sentTo(player, player.getBlockX(), player.getBlockY(), player.getBlockZ())
                || !cubes.sentTo(player, player.getBlockX(), player.getBlockY() - 2, player.getBlockZ())))
            player.resetFallDistance();
    }
}
