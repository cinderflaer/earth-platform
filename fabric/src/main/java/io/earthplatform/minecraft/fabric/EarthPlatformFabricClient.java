package io.earthplatform.minecraft.fabric;

import fuzs.forgeconfigapiport.fabric.api.v5.client.ConfigScreenFactoryRegistry;
import com.mojang.blaze3d.vertex.PoseStack;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.client.CubicClient;
import io.earthplatform.minecraft.cubic.client.Flawless;
import io.earthplatform.minecraft.cubic.client.PerfExportCommand;
import io.earthplatform.minecraft.cubic.compat.CubicCompat;
import io.earthplatform.minecraft.cubic.jet.JetHud;
import io.earthplatform.minecraft.cubic.play.client.EarthCompassHud;
import io.earthplatform.minecraft.cubic.play.client.EarthFlight;
import io.earthplatform.minecraft.cubic.play.client.EarthPlayClient;
import io.earthplatform.minecraft.fabric.axiom.AxiomCubes;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.AbstractLevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * The Fabric client entry point. Level loading, frames, fog and the
 * debug screen have no Fabric API events with NeoForge's timing, so mixins in
 * {@code io.earthplatform.minecraft.fabric.mixin} call the game code at those points.
 */
public final class EarthPlatformFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricPayloads.registerClient();
        // Forge Config API Port's Mod Menu integration (optional: it is only used when Mod Menu is installed) lists a
        // mod's config screen from this registry, so Mods > Earth Platform > Config opens the same screen as on NeoForge.
        ConfigScreenFactoryRegistry.INSTANCE.register(EarthPlatformMod.MOD_ID, (modId, parent) -> new ConfigurationScreen(modId, parent));
        KeyMappingHelper.registerKeyMapping(EarthFlight.KEY);
        KeyMappingHelper.registerKeyMapping(io.earthplatform.minecraft.cubic.play.client.EarthPostcardScreen.KEY);
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> Flawless.offer());

        ClientTickEvents.START_CLIENT_TICK.register(client -> CubicClient.tickStart());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CubicClient.tick();
            CubicCompat.tick();
            EarthFlight.clientTick();
            io.earthplatform.minecraft.cubic.play.client.EarthPostcardScreen.clientTick();
            EarthCompassHud.tick();
            JetHud.tick();
        });
        if (FabricLoader.getInstance().isModLoaded("axiom")) ClientTickEvents.END_CLIENT_TICK.register(client -> AxiomCubes.flush(true));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EarthPlayClient.loggedOut();
            EarthCompassHud.loggedOut();
            CubicCompat.logout();
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "hud"), (graphics, delta) -> {
            CubicClient.waiting(graphics);
            EarthPlayClient.hud(graphics);
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            EarthPlayClient.createWorld(screen);
            EarthPlayClient.pauseMenu(screen, Screens.getWidgets(screen), Screens.getWidgets(screen)::add);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> PerfExportCommand.register(dispatcher));

        // NeoForge's level render stages. Since 26.x the level draws in one main pass of a frame graph, and
        // Fabric API's level render events fire inside it: after the opaque chunk layers (solid and cutout),
        // after the solid features (entities and block entities), after the translucent layer and after the
        // translucent features (particles among them).
        LevelRenderEvents.AFTER_OPAQUE_TERRAIN.register(context -> stage(context, CubicClient.Stage.AFTER_CUTOUT_BLOCKS));
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(context -> {
            stage(context, CubicClient.Stage.AFTER_ENTITIES);
            stage(context, CubicClient.Stage.AFTER_BLOCK_ENTITIES);
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> stage(context, CubicClient.Stage.AFTER_TRANSLUCENT_BLOCKS));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> stage(context, CubicClient.Stage.AFTER_PARTICLES));
    }

    /** One stage of the level, with the main camera, its culling frustum and the frame's matrices. */
    private static void stage(AbstractLevelRenderContext context, CubicClient.Stage stage) {
        CameraRenderState camera = context.levelState().cameraRenderState;
        if (camera == null || !camera.initialized) return;
        Minecraft minecraft = Minecraft.getInstance();
        CubicClient.render(stage, minecraft.gameRenderer.mainCamera(), camera.cullFrustum, camera.viewRotationMatrix, camera.projectionMatrix,
                new PoseStack(), minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
    }
}
