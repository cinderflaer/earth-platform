package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubicWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Falling rain, its splashes and its sound match the server's rain at real mountain heights (see
 * CubicWeather). Since 26.x the weather renderer and the rain ticks both ask the client level, so
 * this changes its answer. Optional, so a mod that draws its own weather still loads.
 */
@Mixin(ClientLevel.class)
abstract class CubicRainRenderMixin {
    @WrapOperation(method = "getPrecipitationAt", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private Biome.Precipitation earthplatform$rain(Biome biome, BlockPos pos, int seaLevel, Operation<Biome.Precipitation> original) {
        return CubicWeather.precipitation((ClientLevel) (Object) this, biome, original.call(biome, pos, seaLevel));
    }
}
