package io.earthplatform.minecraft.platform;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
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

/**
 * Client calls that differ between NeoForge and Fabric: NeoForge's block models take extra model
 * data and choose their own render layers, and fluids name their textures and tints through
 * NeoForge's fluid types; on Fabric these are vanilla's (and Fabric API's fluid handlers).
 * Model data travels as a plain object, null for none.
 */
public interface ClientPlatform {
    ClientPlatform INSTANCE = Loader.load(ClientPlatform.class);

    /** A block entity's model data, or null when it has none. */
    @Nullable Object blockEntityModelData(BlockEntity entity);

    /** The model data a block model draws with at a spot, from what its block entity supplied (or null). */
    @Nullable Object modelData(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state, @Nullable Object fromEntity);

    /**
     * The chunk layers a block model draws into. Since 26.x each quad names its own layer, so these
     * are the layers of the model's quads, for the parts the random source picks.
     */
    Iterable<ChunkSectionLayer> renderTypes(BlockStateModel model, BlockState state, RandomSource random, @Nullable Object data);

    /** A block model's quads on one side (null: the unculled ones), only those of one layer unless it is null. */
    List<BakedQuad> quads(BlockStateModel model, BlockState state, @Nullable Direction side, RandomSource random, @Nullable Object data, @Nullable ChunkSectionLayer type);

    TextureAtlasSprite particleIcon(BlockStateModel model);

    /**
     * Draws the quads of one layer of a block model into an output, at x, y, z (the block's place in
     * the mesh), culling faces against neighbours, with the model parts the seed picks.
     */
    void renderBatched(ModelBlockRenderer blocks, BlockStateModel model, BlockState state, BlockPos pos, BlockAndTintGetter level,
                       float x, float y, float z, BlockQuadOutput output, long seed, @Nullable Object data, ChunkSectionLayer type);

    /** A fluid's colour multiplier at a spot (ARGB; -1 for none). */
    int fluidTint(FluidState fluid, BlockAndTintGetter level, BlockPos pos);

    /** A fluid's still texture on the block atlas. */
    TextureAtlasSprite fluidStill(FluidState fluid, BlockAndTintGetter level, BlockPos pos);

    /** A sprite's pixels as loaded, before mipmaps. */
    NativeImage originalImage(SpriteContents contents);

    /** The mod's full settings screen (NeoForge's config screen, also on Fabric). */
    Screen configScreen(Screen parent);
}
