package io.earthplatform.minecraft.fabric.mixin;

import io.earthplatform.minecraft.cubic.client.CubicClient;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The debug screen's left column, as NeoForge's CustomizeGuiOverlayEvent.DebugText hands it out:
 * since 26.x the overlay gathers its lines into two lists and draws each with extractLines, the
 * left one first (true).
 */
@Mixin(DebugScreenOverlay.class)
abstract class DebugScreenMixin {
    @Inject(method = "extractLines", at = @At("HEAD"))
    private void earthplatform$cubicLines(GuiGraphicsExtractor graphics, List<String> lines, boolean left, CallbackInfo info) {
        if (left) CubicClient.debugText(lines);
    }
}
