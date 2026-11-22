package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.minecraft.cubic.far.Far;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.gen.FarColumn;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * The ground beyond the Earth's map: what {@link EarthGenerator} hands every cube and far column that is not wholly on
 * the map. Dramatic, deterministic and cheap: terraced plateaus, stepped spires and floating islands over the whole
 * 32,768-block height, from {@link FarlandsTerrain}'s arithmetic, with no data to download and nothing that can fail.
 * No caves, trees, structures or water: just stone, snow and a little grass.
 */
public final class FarlandsGenerator implements CubeGenerator {
    public static final String ID = "farlands";

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final Strategy<BlockState> BLOCK_STRATEGY = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
    /** Block states by {@link FarlandsTerrain} material. */
    private static final BlockState[] BLOCKS = {
            AIR, Blocks.BEDROCK.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
            Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(), Blocks.TUFF.defaultBlockState(),
            Blocks.CALCITE.defaultBlockState(), Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.PACKED_ICE.defaultBlockState(), Blocks.BLACKSTONE.defaultBlockState()};

    private final FarlandsTerrain terrain;
    private final Strategy<Holder<Biome>> biomeStrategy;
    /** Biomes by {@link FarlandsTerrain#biome}. */
    private final Holder<Biome>[] biomes;

    @SuppressWarnings("unchecked")
    public FarlandsGenerator(long seed, Registry<Biome> registry, double half, @Nullable FarlandsTerrain.EdgeGround edge) {
        this.terrain = new FarlandsTerrain(seed, half, edge);
        this.biomeStrategy = Strategy.createForBiomes(registry.asHolderIdMap());
        this.biomes = new Holder[]{registry.getOrThrow(Biomes.MEADOW), registry.getOrThrow(Biomes.STONY_PEAKS), registry.getOrThrow(Biomes.FROZEN_PEAKS)};
    }

    public FarlandsTerrain terrain() { return terrain; }

    @Override public String id() { return ID; }
    @Override public int seaLevel() { return EarthGenerator.SEA; }
    @Override public String place(int x, int z) { return "Farlands " + x + ", " + z; }

    // Heights ------------------------------------------------------------------------------------

    @Override public int surfaceY(int x, int z) { return terrain.groundTop(0, x, z); }

    @Override public int skyTopY(int x, int z) {
        int[] layers = new int[FarlandsTerrain.MAX_LAYERS * FarlandsTerrain.STRIDE];
        int count = terrain.layers(0, x, z, layers);
        return layers[(count - 1) * FarlandsTerrain.STRIDE + 1];
    }

    @Override public int lowestGround(int cubeX, int cubeZ) {
        int low = Integer.MAX_VALUE;
        for (int i = 0; i < 256; i++) low = Math.min(low, surfaceY((cubeX << 4) + (i & 15), (cubeZ << 4) + (i >> 4)));
        return low;
    }

    @Override public int highestSurface(int cubeX, int cubeZ) {
        int high = Integer.MIN_VALUE;
        for (int i = 0; i < 256; i++) high = Math.max(high, skyTopY((cubeX << 4) + (i & 15), (cubeZ << 4) + (i >> 4)));
        return high;
    }

    @Override public @Nullable Holder<Biome> biome(int x, int y, int z) {
        int[] layers = new int[FarlandsTerrain.MAX_LAYERS * FarlandsTerrain.STRIDE];
        return biomeAt(layers, terrain.layers(0, x, z, layers), y);
    }

    /** The biome of the run holding a height, else of the nearest run below it, else of the lowest. */
    private Holder<Biome> biomeAt(int[] layers, int count, int y) {
        int pick = 0;
        for (int i = 0; i < count; i++) if (layers[i * FarlandsTerrain.STRIDE] <= y) pick = i;
        return biomes[FarlandsTerrain.biome(layers[pick * FarlandsTerrain.STRIDE + 2], layers[pick * FarlandsTerrain.STRIDE + 1])];
    }

    // Cubes --------------------------------------------------------------------------------------

    @Override public LevelChunkSection generate(int cubeX, int cubeY, int cubeZ) {
        int x0 = cubeX << 4, y0 = cubeY << 4, z0 = cubeZ << 4, stride = FarlandsTerrain.STRIDE;
        int[][] columns = new int[256][];
        int[] counts = new int[256];
        int[] scratch = new int[FarlandsTerrain.MAX_LAYERS * stride];
        boolean any = false;
        for (int i = 0; i < 256; i++) {
            counts[i] = terrain.layers(0, x0 + (i & 15), z0 + (i >> 4), scratch);
            columns[i] = java.util.Arrays.copyOf(scratch, counts[i] * stride);
            for (int l = 0; l < counts[i] && !any; l++) any = columns[i][l * stride] <= y0 + 15 && columns[i][l * stride + 1] >= y0;
        }
        PalettedContainer<Holder<Biome>> biome = new PalettedContainer<>(biomeAt(columns[8 * 16 + 8], counts[8 * 16 + 8], y0 + 8), biomeStrategy);
        for (int qz = 0; qz < 4; qz++) for (int qx = 0; qx < 4; qx++) {
            int i = (qz * 4 + 1) * 16 + qx * 4 + 1;
            Holder<Biome> here = biomeAt(columns[i], counts[i], y0 + 8);
            for (int qy = 0; qy < 4; qy++) biome.set(qx, qy, qz, here);
        }
        LevelChunkSection section = new LevelChunkSection(new PalettedContainer<>(AIR, BLOCK_STRATEGY), biome);
        if (any) for (int i = 0; i < 256; i++) {
            int[] column = columns[i];
            for (int l = 0; l < counts[i]; l++) {
                int bottom = column[l * stride], top = column[l * stride + 1], kind = column[l * stride + 2];
                for (int y = Math.max(y0, bottom); y <= Math.min(y0 + 15, top); y++)
                    section.setBlockState(i & 15, y - y0, i >> 4, BLOCKS[FarlandsTerrain.material(kind, y, top, bottom)], false);
            }
        }
        section.recalcBlockCounts();
        return section;
    }

    // Far terrain --------------------------------------------------------------------------------

    /**
     * Voxels of {@code 2^level} blocks. A voxel is solid where a run covers at least half of it, and then takes the
     * material of the run at its top block, so every run's top voxel is grass or snow. Columns taller than the section
     * budget keep their top: the stack below its lowest section counts as ground.
     */
    @Override public void farColumn(int level, int columnX, int columnZ, FarColumn out) {
        out.packet = null;
        int v = 1 << level, width = 16 * v, x0 = columnX * width, z0 = columnZ * width, centre = v >> 1, stride = FarlandsTerrain.STRIDE;
        int[][] columns = new int[256][];
        int[] counts = new int[256], scratch = new int[FarlandsTerrain.MAX_LAYERS * stride];
        int lowGround = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int i = 0; i < 256; i++) {
            counts[i] = terrain.layers(level, x0 + (i & 15) * v + centre, z0 + (i >> 4) * v + centre, scratch);
            columns[i] = java.util.Arrays.copyOf(scratch, counts[i] * stride);
            lowGround = Math.min(lowGround, columns[i][1]);
            high = Math.max(high, columns[i][(counts[i] - 1) * stride + 1]);
        }
        int bottom = (lowGround - v) >> (level + 4), highest = high >> (level + 4);
        bottom = Math.max(bottom, highest - Far.MAX_SECTIONS + 1);
        out.bottom = bottom;
        java.util.Arrays.fill(out.drops, (short) 0);
        for (int si = 0; si <= highest - bottom; si++) {
            int sectionY = (bottom + si) * width, sectionTop = sectionY + width - 1;
            boolean touched = false;
            for (int i = 0; i < 256 && !touched; i++)
                for (int l = 0; l < counts[i]; l++) if (columns[i][l * stride] <= sectionTop && columns[i][l * stride + 1] >= sectionY) { touched = true; break; }
            if (!touched) { out.sections.add(null); continue; }
            PalettedContainer<Holder<Biome>> biome = new PalettedContainer<>(biomeAt(columns[8 * 16 + 8], counts[8 * 16 + 8], sectionY + (width >> 1)), biomeStrategy);
            for (int qz = 0; qz < 4; qz++) for (int qx = 0; qx < 4; qx++) {
                int i = (qz * 4 + 1) * 16 + qx * 4 + 1;
                Holder<Biome> here = biomeAt(columns[i], counts[i], sectionY + (width >> 1));
                for (int qy = 0; qy < 4; qy++) biome.set(qx, qy, qz, here);
            }
            LevelChunkSection section = new LevelChunkSection(new PalettedContainer<>(AIR, BLOCK_STRATEGY), biome);
            for (int i = 0; i < 256; i++) {
                int[] column = columns[i];
                for (int vy = 0; vy < 16; vy++) {
                    int y0 = sectionY + vy * v, y1 = y0 + v - 1;
                    for (int l = 0; l < counts[i]; l++) {
                        int lo = column[l * stride], top = column[l * stride + 1];
                        if (lo > y1 || top < y0) continue;
                        if (Math.min(top, y1) - Math.max(lo, y0) + 1 < Math.max(1, v >> 1)) continue;
                        section.setBlockState(i & 15, vy, i >> 4, BLOCKS[FarlandsTerrain.material(column[l * stride + 2], Math.min(y1, top), top, lo)], false);
                        break;
                    }
                }
            }
            section.recalcBlockCounts();
            out.sections.add(section.hasOnlyAir() ? null : section);
        }
    }
}
