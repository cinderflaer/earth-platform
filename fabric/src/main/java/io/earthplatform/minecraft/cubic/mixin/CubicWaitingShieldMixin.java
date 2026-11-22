package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubeHold;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.jet.JetStream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player the client holds in place until the cubes at their feet arrive (after a far teleport, or rejoining a
 * world at the saved spot) takes no damage meanwhile, as vanilla's player is invulnerable until the client has
 * loaded: the cubes they stand in and on are not there to read, and whatever they stand in would hurt.
 */
@Mixin(ServerPlayer.class)
abstract class CubicWaitingShieldMixin {
    @Inject(method = "isInvulnerableTo", at = @At("HEAD"), cancellable = true)
    private void earthplatform$waitingForCubes(ServerLevel level, DamageSource source, CallbackInfoReturnable<Boolean> result) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (!(CubicLevel.of(level) instanceof ServerCubeWorld cubes)) return;
        int x = player.getBlockX(), y = player.getBlockY(), z = player.getBlockZ();
        boolean waiting = !cubes.sentTo(player, x, y, z) || !cubes.sentTo(player, x, y - 2, z);
        boolean exempt = player.isSpectator() || JetStream.aboveTerrain(player);
        boolean kill = source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD) && CubeHold.belowWorld(player.getY());
        if (CubeHold.shields(waiting, exempt, kill)) result.setReturnValue(true);
    }
}
