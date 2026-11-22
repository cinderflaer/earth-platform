package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The client's ambient ticks, which try about 1,300 random blocks round the player every tick for
 * flames, drips and biome particles, look up less in the cubic level: the fluid comes from the block
 * state already read. (Since 26.x biome particles come from the environment attributes, which no
 * longer read the biome here, so the cube's own biome shortcut is gone.)
 */
@Mixin(ClientLevel.class)
abstract class CubicAnimateTickMixin {
    @WrapOperation(method = "doAnimateTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState earthplatform$fluid(ClientLevel level, BlockPos pos, Operation<FluidState> original, @Local BlockState state) {
        return CubicLevel.at(level, pos.getX(), pos.getZ()) != null ? state.getFluidState() : original.call(level, pos);
    }
}
