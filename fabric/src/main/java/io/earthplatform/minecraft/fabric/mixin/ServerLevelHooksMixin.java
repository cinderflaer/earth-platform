package io.earthplatform.minecraft.fabric.mixin;

import io.earthplatform.minecraft.cubic.CubicEvents;
import io.earthplatform.minecraft.cubic.play.EarthBasics;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NeoForge's level save and sleep-finished events, where NeoForge fires them. */
@Mixin(ServerLevel.class)
abstract class ServerLevelHooksMixin {
    /** After the chunks and entities are written, as NeoForge's LevelEvent.Save. */
    @Inject(method = "save", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;autoSave()V", shift = At.Shift.AFTER))
    private void earthplatform$savedAuto(CallbackInfo info) { CubicEvents.save((ServerLevel) (Object) this); }

    @Inject(method = "save", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;saveAll()V", shift = At.Shift.AFTER))
    private void earthplatform$savedAll(CallbackInfo info) { CubicEvents.save((ServerLevel) (Object) this); }

    /**
     * A night slept through, as NeoForge's SleepFinishedTimeEvent. Since 26.x the night is skipped
     * by moving the dimension's clock to its wake-up marker; the new time is that clock's.
     */
    @Inject(method = "tick", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/clock/ServerClockManager;moveToTimeMarker(Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceKey;)Z"))
    private void earthplatform$sleptThrough(BooleanSupplier haveTime, CallbackInfo info) {
        ServerLevel level = (ServerLevel) (Object) this;
        EarthBasics.sleptThrough(level, level.getDefaultClockTime());
    }
}
