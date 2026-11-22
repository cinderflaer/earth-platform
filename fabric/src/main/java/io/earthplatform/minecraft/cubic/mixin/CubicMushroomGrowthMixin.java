package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A mushroom grows into a huge mushroom at any cubic height. */
@Mixin(AbstractHugeMushroomFeature.class)
abstract class CubicMushroomGrowthMixin {
    @WrapOperation(method = "isValidPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinY()I"))
    private int earthplatform$cubicBottom(WorldGenLevel level, Operation<Integer> original) {
        return level instanceof Level live && CubicLevel.of(live) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = "isValidPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMaxY()I"))
    private int earthplatform$cubicTop(WorldGenLevel level, Operation<Integer> original) {
        return level instanceof Level live && CubicLevel.of(live) != null ? CubeWorld.MAX_Y : original.call(level);
    }
}
