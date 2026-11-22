package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.portal.CubicPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Nether portal in the Nether leads back to the cubic Earth for those who came from it, and a
 * portal in the cubic Earth leads to the Nether with the height mapped by {@link CubicPortals#toNetherY}.
 */
@Mixin(NetherPortalBlock.class)
abstract class CubicPortalMixin {
    /** Vanilla's lookup or creation of the exit portal in the destination level, with the destination point in its Y range. */
    @Shadow
    abstract TeleportTransition getExitPortal(ServerLevel level, Entity entity, BlockPos portal, BlockPos target, boolean toNether, WorldBorder border);

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void earthplatform$toCubic(ServerLevel level, Entity entity, BlockPos pos, CallbackInfoReturnable<TeleportTransition> result) {
        if (level.dimension() == Level.NETHER || Cubic.isNether(level.dimension())) {
            TeleportTransition transition = CubicPortals.fromNether(level, entity, pos);
            if (transition != null) result.setReturnValue(transition);
        } else if (Cubic.isEarth(level.dimension())) {
            TeleportTransition tall = CubicPortals.toCubicNether(level, entity, pos);
            if (tall != null) { result.setReturnValue(tall); return; }
            // Vanilla scales X and Z by 1/8 but passes Y through, which is a surface height up to 16384 here.
            ServerLevel nether = level.getServer().getLevel(Level.NETHER);
            if (nether == null) return;
            WorldBorder border = nether.getWorldBorder();
            double scale = DimensionType.getTeleportationScale(level.dimensionType(), nether.dimensionType());
            BlockPos target = border.clampToBounds(entity.getX() * scale, CubicPortals.toNetherY(entity.getY()), entity.getZ() * scale);
            result.setReturnValue(getExitPortal(nether, entity, pos, target, true, border));
        }
    }
}
