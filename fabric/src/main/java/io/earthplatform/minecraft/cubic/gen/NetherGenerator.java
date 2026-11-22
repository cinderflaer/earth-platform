package io.earthplatform.minecraft.cubic.gen;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * The tall cubic Nether: storeys of netherrack caverns stacked every {@link #PERIOD} blocks between
 * {@link #MIN_Y} and {@link #MAX_Y}, each with a lava sea on its floor, and solid netherrack beyond
 * both ends (no bedrock). Storey 0 matches the vanilla Nether: lava sea at Y 32, cavern up to about
 * Y 120, so {@link io.earthplatform.minecraft.cubic.portal.NetherHeight} maps Earth heights onto it.
 * A pure function of seed and position, safe from any thread.
 */
public final class NetherGenerator implements CubeGenerator {
    public static final String ID = "nether-v1";
    /** The generated range; above and below it the world is solid netherrack. */
    public static final int MIN_Y = -2048, MAX_Y = 2047;
    /** Blocks from one storey to the next, and the lava sea's height over a storey's floor band centre. */
    public static final int PERIOD = 128, LAVA_OVER_FLOOR = 32;
    /** The top of the cubic world, above which nothing is stored. */
    private static final int WORLD_TOP = 16383;

    /** Lava level of the storey containing a height. */
    public static int lavaLevel(int y) { return Math.floorDiv(y, PERIOD) * PERIOD + LAVA_OVER_FLOOR; }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), NETHERRACK = Blocks.NETHERRACK.defaultBlockState(),
            LAVA = Blocks.LAVA.defaultBlockState(), QUARTZ = Blocks.NETHER_QUARTZ_ORE.defaultBlockState(),
            GOLD = Blocks.NETHER_GOLD_ORE.defaultBlockState(), DEBRIS = Blocks.ANCIENT_DEBRIS.defaultBlockState(),
            MAGMA = Blocks.MAGMA_BLOCK.defaultBlockState(), GLOWSTONE = Blocks.GLOWSTONE.defaultBlockState(),
            SOUL_SAND = Blocks.SOUL_SAND.defaultBlockState(), SOUL_SOIL = Blocks.SOUL_SOIL.defaultBlockState(),
            CRIMSON = Blocks.CRIMSON_NYLIUM.defaultBlockState(), WARPED = Blocks.WARPED_NYLIUM.defaultBlockState(),
            BLACKSTONE = Blocks.BLACKSTONE.defaultBlockState(), GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final Strategy<BlockState> BLOCK_STRATEGY = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);

    private enum Kind { WASTES, SOUL, CRIMSON, WARPED, DELTAS }

    private final long seed;
    private final Strategy<Holder<Biome>> biomeStrategy;
    private final SimplexNoise large, small, shafts, climate, climate2;
    private final Holder<Biome> wastes, soul, crimson, warped, deltas;

    public NetherGenerator(long seed, Registry<Biome> biomes) {
        this.seed = seed;
        this.biomeStrategy = Strategy.createForBiomes(biomes.asHolderIdMap());
        LegacyRandomSource random = new LegacyRandomSource(seed ^ 0x4e45544845524cL);
        large = new SimplexNoise(random); small = new SimplexNoise(random); shafts = new SimplexNoise(random);
        climate = new SimplexNoise(random); climate2 = new SimplexNoise(random);
        wastes = biomes.getOrThrow(Biomes.NETHER_WASTES); soul = biomes.getOrThrow(Biomes.SOUL_SAND_VALLEY);
        crimson = biomes.getOrThrow(Biomes.CRIMSON_FOREST); warped = biomes.getOrThrow(Biomes.WARPED_FOREST);
        deltas = biomes.getOrThrow(Biomes.BASALT_DELTAS);
    }

    @Override public String id() { return ID; }
    @Override public int seaLevel() { return 32; }
    /** The roof of the generated range: the nearest thing the Nether has to a ground. */
    @Override public int surfaceY(int x, int z) { return MAX_Y; }
    /** Rock fills the world above the range, so no skylight reaches any cavern. */
    @Override public int skyTopY(int x, int z) { return WORLD_TOP; }
    /** Only what is under the generated range is known to be buried. */
    @Override public int lowestGround(int cubeX, int cubeZ) { return MIN_Y; }
    @Override public int lowestSurface(int cubeX, int cubeZ) { return MIN_Y; }
    @Override public int highestSurface(int cubeX, int cubeZ) { return MAX_Y; }
    @Override public boolean farTerrain() { return false; }
    @Override public void farColumn(int level, int columnX, int columnZ, FarColumn out) { out.bottom = Integer.MAX_VALUE >> 6; }

    private Kind kind(int x, int z) {
        double a = climate.getValue(x / 220.0, z / 220.0), b = climate2.getValue(x / 160.0, z / 160.0);
        if (a > 0.45) return Kind.CRIMSON;
        if (a < -0.45) return Kind.WARPED;
        if (b > 0.45) return Kind.DELTAS;
        if (b < -0.4) return Kind.SOUL;
        return Kind.WASTES;
    }

    private Holder<Biome> biome(Kind kind) {
        return switch (kind) { case SOUL -> soul; case CRIMSON -> crimson; case WARPED -> warped; case DELTAS -> deltas; default -> wastes; };
    }

    /** Solidity: positive is rock. */
    public double density(int x, int y, int z) {
        if (y < MIN_Y || y > MAX_Y) return 4;
        double band = -Math.cos(2 * Math.PI * (y - 64) / PERIOD);
        double shaft = shafts.getValue(x / 110.0, z / 110.0);
        double weight = 1 - Mth.clamp((shaft - 0.62) / 0.1, 0, 1);
        double detail = 0.55 * large.getValue(x / 48.0, y / 32.0, z / 48.0) + 0.2 * small.getValue(x / 16.0, y / 12.0, z / 16.0);
        double edge = Math.max(Math.max(0, (MIN_Y + 48 - y) / 48.0), Math.max(0, (y - (MAX_Y - 48)) / 48.0));
        return 0.62 * band * weight + detail + 2 * edge;
    }

    private long hash(int x, int y, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 32; h *= 0xD6E8FEB86659FD93L; h ^= h >>> 32;
        return h & Long.MAX_VALUE;
    }

    @Override public LevelChunkSection generate(int cx, int cy, int cz) {
        int x0 = cx << 4, y0 = cy << 4, z0 = cz << 4;
        if (y0 + 15 < MIN_Y - 1 || y0 > MAX_Y + 1) {
            LevelChunkSection solid = new LevelChunkSection(new PalettedContainer<>(NETHERRACK, BLOCK_STRATEGY), new PalettedContainer<>(wastes, biomeStrategy));
            solid.recalcBlockCounts();
            return solid;
        }
        // Density on a 4-block grid from one layer under the cube to one over it, interpolated between.
        double[] grid = new double[5 * 6 * 5];
        for (int i = 0; i < 5; i++) for (int j = 0; j < 6; j++) for (int k = 0; k < 5; k++)
            grid[(i * 6 + j) * 5 + k] = density(x0 + 4 * i, y0 - 4 + 4 * j, z0 + 4 * k);
        // rock[l] is the layer at y0 - 1 + l.
        boolean[][][] rock = new boolean[18][16][16];
        for (int l = 0; l < 18; l++) {
            int y = y0 - 1 + l;
            double gy = (y - (y0 - 4)) / 4.0;
            int j = Math.min((int) gy, 4);
            double fy = gy - j;
            for (int x = 0; x < 16; x++) {
                int i = x >> 2;
                double fx = (x & 3) / 4.0;
                for (int z = 0; z < 16; z++) {
                    int k = z >> 2;
                    double fz = (z & 3) / 4.0, v = 0;
                    for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int c = 0; c < 2; c++)
                        v += grid[((i + a) * 6 + j + b) * 5 + k + c] * (a == 0 ? 1 - fx : fx) * (b == 0 ? 1 - fy : fy) * (c == 0 ? 1 - fz : fz);
                    rock[l][x][z] = y < MIN_Y || y > MAX_Y || v > 0;
                }
            }
        }
        PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>(wastes, biomeStrategy);
        Kind[] kinds = new Kind[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) kinds[z * 16 + x] = kind(x0 + x, z0 + z);
        for (int qz = 0; qz < 4; qz++) for (int qx = 0; qx < 4; qx++) {
            Holder<Biome> holder = biome(kinds[(qz * 4 + 2) * 16 + qx * 4 + 2]);
            for (int qy = 0; qy < 4; qy++) biomes.set(qx, qy, qz, holder);
        }
        LevelChunkSection section = new LevelChunkSection(new PalettedContainer<>(AIR, BLOCK_STRATEGY), biomes);
        for (int ly = 0; ly < 16; ly++) {
            int y = y0 + ly;
            for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                BlockState state;
                if (rock[ly + 1][lx][lz]) {
                    boolean airAbove = !rock[ly + 2][lx][lz], airBelow = !rock[ly][lx][lz];
                    boolean lavaAbove = airAbove && y + 1 <= lavaLevel(y + 1);
                    state = rockBlock(x0 + lx, y, z0 + lz, kinds[lz * 16 + lx], airAbove, airBelow, lavaAbove);
                } else {
                    state = y <= lavaLevel(y) ? LAVA : AIR;
                }
                if (state != AIR) section.setBlockState(lx, ly, lz, state, false);
            }
        }
        section.recalcBlockCounts();
        return section;
    }

    private BlockState rockBlock(int x, int y, int z, Kind kind, boolean airAbove, boolean airBelow, boolean lavaAbove) {
        long h = hash(x, y, z);
        if (lavaAbove) return h % 3 == 0 ? MAGMA : NETHERRACK;
        if (airAbove) {
            switch (kind) {
                case CRIMSON: return CRIMSON;
                case WARPED: return WARPED;
                case SOUL: return h % 4 == 0 ? SOUL_SOIL : SOUL_SAND;
                case DELTAS: if (h % 3 == 0) return BLACKSTONE; break;
                default:
                    if (h % 23 == 0) return SOUL_SAND;
                    if (h % 31 == 1) return GRAVEL;
            }
        }
        if (airBelow && h % 1500 == 7) return GLOWSTONE;
        long r = h % 20000;
        if (r < 160) return QUARTZ;
        if (r < 210) return GOLD;
        if (r == 210) return DEBRIS;
        return NETHERRACK;
    }
}
