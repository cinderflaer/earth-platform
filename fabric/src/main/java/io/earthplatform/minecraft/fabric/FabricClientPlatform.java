package io.earthplatform.minecraft.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.fabric.mixin.SpriteContentsAccess;
import io.earthplatform.minecraft.platform.ClientPlatform;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * {@link ClientPlatform} on Fabric: vanilla block models (each quad names its chunk layer, no model
 * data) and vanilla's fluid models, which Fabric API's fluid registry fills for modded fluids too.
 */
public final class FabricClientPlatform implements ClientPlatform {
    private static final Direction[] SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    @Override @Nullable public Object blockEntityModelData(BlockEntity entity) { return null; }

    @Override @Nullable public Object modelData(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state, @Nullable Object fromEntity) { return null; }

    @Override public Iterable<ChunkSectionLayer> renderTypes(BlockStateModel model, BlockState state, RandomSource random, @Nullable Object data) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(random, parts);
        EnumSet<ChunkSectionLayer> layers = EnumSet.noneOf(ChunkSectionLayer.class);
        for (BlockStateModelPart part : parts)
            for (Direction side : SIDES)
                for (BakedQuad quad : part.getQuads(side)) layers.add(quad.materialInfo().layer());
        return layers;
    }

    @Override public List<BakedQuad> quads(BlockStateModel model, BlockState state, @Nullable Direction side, RandomSource random, @Nullable Object data, @Nullable ChunkSectionLayer type) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(random, parts);
        if (parts.size() == 1 && type == null) return parts.get(0).getQuads(side);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts)
            for (BakedQuad quad : part.getQuads(side))
                if (type == null || quad.materialInfo().layer() == type) quads.add(quad);
        return quads;
    }

    @Override public TextureAtlasSprite particleIcon(BlockStateModel model) { return model.particleMaterial().sprite(); }

    @Override public void renderBatched(ModelBlockRenderer blocks, BlockStateModel model, BlockState state, BlockPos pos, BlockAndTintGetter level,
                                        float x, float y, float z, BlockQuadOutput output, long seed, @Nullable Object data, ChunkSectionLayer type) {
        blocks.tesselateBlock((qx, qy, qz, quad, instance) -> {
            if (quad.materialInfo().layer() == type) output.put(qx, qy, qz, quad, instance);
        }, x, y, z, level, pos, state, model, seed);
    }

    @Override public int fluidTint(FluidState fluid, BlockAndTintGetter level, BlockPos pos) {
        BlockTintSource tint = model(fluid).tintSource();
        return tint == null ? -1 : tint.colorInWorld(fluid.createLegacyBlock(), level, pos);
    }

    @Override public TextureAtlasSprite fluidStill(FluidState fluid, BlockAndTintGetter level, BlockPos pos) {
        return model(fluid).stillMaterial().sprite();
    }

    private static FluidModel model(FluidState fluid) { return Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid); }

    @Override public NativeImage originalImage(SpriteContents contents) { return ((SpriteContentsAccess) contents).earthplatform$originalImage(); }

    @Override public Screen configScreen(Screen parent) { return new ConfigurationScreen(EarthPlatformMod.MOD_ID, parent); }
}
