package io.earthplatform.minecraft.cubic.mixin;

import net.minecraft.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's placement of an entity in an exit portal, keeping its offset in the portal it left. */
@Mixin(NetherPortalBlock.class)
public interface NetherPortalAccess {
    @Invoker("getDimensionTransitionFromExit")
    static TeleportTransition earthplatform$exit(Entity entity, BlockPos pos, BlockUtil.FoundRectangle exit, ServerLevel level,
                                                 TeleportTransition.PostTeleportTransition post) {
        throw new AssertionError();
    }
}
