package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BigDripleafBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A big dripleaf grows taller at any cubic height. */
@Mixin(BigDripleafBlock.class)
abstract class CubicDripleafMixin {
    @WrapOperation(method = "canGrowInto", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;isInsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean earthplatform$cubicRange(LevelReader level, BlockPos pos, Operation<Boolean> original) {
        return level instanceof Level live && CubicLevel.of(live) != null ? CubeWorld.inRange(pos.getY()) : original.call(level, pos);
    }
}
