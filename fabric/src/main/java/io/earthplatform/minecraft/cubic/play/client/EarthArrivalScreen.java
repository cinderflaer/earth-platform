package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.minecraft.cubic.play.EarthStatus;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

/** Shown while the server fetches the Earth around the world's home, before the first arrival. */
public final class EarthArrivalScreen extends Screen {
    private EarthStatus.Payload status;

    EarthArrivalScreen(EarthStatus.Payload status) {
        super(Component.translatable("earthplatform.arrival.title"));
        this.status = status;
    }

    void update(EarthStatus.Payload status) { this.status = status; }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("earthplatform.arrival.leave"), button -> leave())
                .bounds(width / 2 - 100, height - 40, 200, 20).build());
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
        int y = height / 2 - 50;
        gui.centeredText(font, title, width / 2, y, 0xFFFFFFFF);
        if (!status.place().isEmpty()) gui.centeredText(font, Component.literal(status.place()), width / 2, y + 16, 0xFF9FD7FF);
        Component line, detail;
        int colour = 0xFFCCCCCC;
        switch (status.problem()) {
            case EarthStatus.UNREACHABLE -> {
                line = Component.translatable("earthplatform.arrival.unreachable");
                detail = Component.translatable("earthplatform.arrival.unreachable.hint");
                colour = 0xFFFFCC55;
            }
            case EarthStatus.OFFLINE_MODE -> {
                line = Component.translatable("earthplatform.arrival.offlineMode");
                detail = Component.translatable("earthplatform.arrival.offlineMode.hint");
                colour = 0xFFFFCC55;
            }
            default -> {
                line = Component.translatable("earthplatform.arrival.downloading", ".".repeat((int) (Util.getMillis() / 400 % 4)));
                detail = status.waitedSeconds() >= 20 ? Component.translatable("earthplatform.arrival.slow")
                        : Component.translatable("earthplatform.arrival.firstVisit");
            }
        }
        gui.centeredText(font, line, width / 2, y + 40, colour);
        int lineY = y + 56;
        for (var text : font.split(detail, 300)) { gui.centeredText(font, text, width / 2, lineY, 0xFFAAAAAA); lineY += 11; }
        if (status.waitedSeconds() > 0)
            gui.centeredText(font, Component.translatable("earthplatform.arrival.waited", status.waitedSeconds()), width / 2, lineY + 8, 0xFF777777);
    }

    private void leave() {
        // The pause menu's way out: saves a local world, then the title screen or the server list.
        minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
    }
}
