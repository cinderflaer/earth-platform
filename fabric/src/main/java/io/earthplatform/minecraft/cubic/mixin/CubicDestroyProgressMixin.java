package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.SortedSet;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Block cracks are keyed by BlockPos.asLong, whose 12-bit Y wraps above Y 2047, so the overlay
 * landed thousands of blocks away. Each key's cracks still carry their real position, so the
 * overlay is drawn there. (Since 26.x the cracks are kept by the client level and turned into
 * render state by the level extractor.)
 */
@Mixin(LevelExtractor.class)
abstract class CubicDestroyProgressMixin {
    @WrapOperation(method = "extractBlockDestroyAnimation", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;of(J)Lnet/minecraft/core/BlockPos;"))
    private BlockPos earthplatform$realPosition(long key, Operation<BlockPos> original,
                                                @Local Long2ObjectMap.Entry<SortedSet<BlockDestructionProgress>> entry) {
        SortedSet<BlockDestructionProgress> cracks = entry.getValue();
        return cracks == null || cracks.isEmpty() ? original.call(key) : cracks.last().getPos();
    }
}
