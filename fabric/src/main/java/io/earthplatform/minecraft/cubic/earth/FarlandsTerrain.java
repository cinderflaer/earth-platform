package io.earthplatform.minecraft.cubic.earth;

import javax.annotation.Nullable;

/**
 * The Farlands' terrain as pure arithmetic, with nothing of Minecraft in it: every column beyond the map's poles
 * (|Z| past the Web Mercator edge) is a few solid runs, found from the world seed and the column's block X and Z alone.
 *
 * <p>Each column holds a ground run from the world's floor up to a height made of terraced plateaus, ridges and tall
 * stepped spires, and up to six floating islands one above another between about Y -10,500 and +11,500. Noise is value
 * noise on integer lattices: cell indices come from {@code floorDiv} on ints and the fractions from an exact integer
 * remainder over a power-of-two-ish size, so nothing loses precision however far from the origin a column is (the hard
 * cap is 30,000,000 blocks, where a float would be good to about two blocks).
 *
 * <p>Near the edge the terrain blends into the ground at the map's last row, over {@link #BLEND} blocks, so there is no
 * seam; with no edge ground given it blends toward a low plain at sea level.
 */
public final class FarlandsTerrain {
    /** The world's lowest and highest block Y (the cubic world's range). */
    public static final int MIN_Y = -16384, MAX_Y = 16383;
    /** Blocks short of the map's edge where the Earth stops and the Farlands begin, so the Earth's own reads near neighbours stay on the map. */
    public static final int EDGE_MARGIN = 4;
    /** Blocks beyond the edge over which the terrain grows from the edge ground to its full form. */
    public static final int BLEND = 2048;
    /** Ground when no edge ground is known. */
    public static final int DEFAULT_EDGE = 64;
    /** Ints a layer takes in {@link #layers}' output: bottom Y, top Y (both inclusive) and kind. */
    public static final int STRIDE = 3;
    /** Most layers a column holds: the ground and six islands. */
    public static final int MAX_LAYERS = 7;
    /** Layer kinds. */
    public static final int GROUND = 0, ISLAND = 1;
    /** Materials; a generator maps them to blocks. */
    public static final int AIR = 0, BEDROCK = 1, GRASS = 2, DIRT = 3, STONE = 4, DEEPSLATE = 5, ANDESITE = 6, TUFF = 7,
            CALCITE = 8, SNOW = 9, PACKED_ICE = 10, BLACKSTONE = 11;

    private static final int ISLAND_BANDS = 6, ISLAND_Y0 = -10500, ISLAND_STEP = 4400;
    private static final int TOP_CLEARANCE = 2048;

    /** Ground of the map's last row, for blending: the level, the block X, and the block Z of that row (always on the map). */
    public interface EdgeGround { int top(int level, int x, int z); }

    private final long seed;
    private final double half;
    @Nullable private final EdgeGround edge;

    /**
     * @param half the map's half extent in blocks ({@code MercatorGrid.worldWidthBlocks() / 2}), where the Farlands start
     * @param edge the ground of the map's edge row, or null for a low plain
     */
    public FarlandsTerrain(long seed, double half, @Nullable EdgeGround edge) {
        this.seed = seed;
        this.half = half;
        this.edge = edge;
    }

    // Where the map ends -------------------------------------------------------------------------

    /** Whether a block row Z lies on the map proper: at least {@link #EDGE_MARGIN} blocks inside its edge. */
    public static boolean inside(double half, double z) { return Math.abs(z) <= half - EDGE_MARGIN; }

    /** Whether every block a cube column reads (its 16 and a couple round them) is on the map, so the Earth makes it. */
    public static boolean cubeColumnInside(double half, int cubeZ) {
        long z0 = (long) cubeZ << 4;
        return inside(half, z0 - 2) && inside(half, z0 + 17);
    }

    /** Whether every sample of a far column (its 16 voxels and a voxel round them) is on the map. */
    public static boolean farColumnInside(double half, int level, int columnZ) {
        long v = 1L << level, z0 = columnZ * 16L * v;
        return inside(half, z0 - v - 1) && inside(half, z0 + 17 * v + 1);
    }

    // Terrain ------------------------------------------------------------------------------------

    /**
     * The solid runs of a column from the bottom up, {@link #STRIDE} ints each (bottom, top, kind), the ground run
     * first; runs never touch or overlap. Returns how many.
     *
     * @param level the far level it is for (0 for cubes), passed on to the edge ground
     */
    public int layers(int level, int x, int z, int[] out) {
        double dist = Math.abs((double) z) - half;
        double fade = dist <= 0 ? 0 : dist >= BLEND ? 1 : smooth(dist / BLEND);
        int top = ground(level, x, z, fade);
        out[0] = MIN_Y; out[1] = top; out[2] = GROUND;
        int count = 1;
        // No islands over the blend: they would hang in front of the edge's plain.
        if (fade < 0.6) return count;
        for (int k = 0; k < ISLAND_BANDS; k++) {
            double n = fbm(10 + k, x, z, 2048, 3);
            if (n <= 0.58) continue;
            double s = Math.min(1, (n - 0.58) / 0.42);
            int ytop = ISLAND_Y0 + k * ISLAND_STEP + (int) (s * 90) + (int) (40 * (fbm(30 + k, x, z, 96, 2) - 0.5));
            int bottom = ytop - (int) (24 + 900 * s * s);
            int last = (count - 1) * STRIDE;
            if (bottom <= out[last + 1] + 1) { out[last + 1] = Math.max(out[last + 1], ytop); continue; }
            out[count * STRIDE] = bottom; out[count * STRIDE + 1] = ytop; out[count * STRIDE + 2] = ISLAND;
            count++;
        }
        return count;
    }

    /** The top of a column's ground run alone (no islands): what {@link #layers} gives first. */
    public int groundTop(int level, int x, int z) {
        double dist = Math.abs((double) z) - half;
        return ground(level, x, z, dist <= 0 ? 0 : dist >= BLEND ? 1 : smooth(dist / BLEND));
    }

    private int ground(int level, int x, int z, double fade) {
        double raw = fade <= 0 ? 0 : raw(x, z);
        double h;
        if (fade >= 1) h = raw;
        else {
            double base = edge == null ? DEFAULT_EDGE : edge.top(level, x, edgeRow(z));
            h = base + (raw - base) * fade;
        }
        return (int) Math.max(MIN_Y + 8, Math.min(MAX_Y - TOP_CLEARANCE, Math.floor(h)));
    }

    /** The map's last block row on the side of {@code z}. */
    private int edgeRow(int z) {
        int row = (int) Math.floor(half - EDGE_MARGIN);
        return z < 0 ? -row : row;
    }

    /** The terrain at full strength: terraced plateaus, ridges and spires, between about -6,000 and +15,000. */
    private double raw(int x, int z) {
        double plateau = terrace(fbm(1, x, z, 4096, 4));
        double ridge = 1 - Math.abs(2 * fbm(2, x, z, 1536, 5) - 1);
        double mask = fbm(3, x, z, 6144, 2);
        double h = -6000 + plateau * 13000 + ridge * ridge * mask * 4000;
        h += spires(x, z, 1024, 4, 0.30, 80, 520, 2500, 11000);
        h += spires(x, z, 4096, 5, 0.12, 500, 2000, 8000, 15500);
        return h;
    }

    /** Seven flat steps with short steep risers between them: broken plateaus. */
    private static double terrace(double n) {
        double t = n * 7, i = Math.floor(t), f = t - i;
        double riser = f <= 0.7 ? 0 : smooth((f - 0.7) / 0.3);
        return Math.min(1, (i + riser) / 7);
    }

    /** The tallest of the stepped cones of the 3 by 3 cells round a column; at most one cone to a cell. */
    private double spires(int x, int z, int cell, long salt, double chance, int rMin, int rMax, int hMin, int hMax) {
        int cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
        double best = 0;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int ix = cx + dx, iz = cz + dz;
            long h = hash(salt, ix, iz);
            if (unit(h) >= chance) continue;
            long h1 = mix(h + 1), h2 = mix(h + 2), h3 = mix(h + 3), h4 = mix(h + 4);
            double px = ix * (double) cell + cell * (0.15 + 0.7 * unit(h1)), pz = iz * (double) cell + cell * (0.15 + 0.7 * unit(h2));
            double r = rMin + unit(h3) * (rMax - rMin);
            double ex = x + 0.5 - px, ez = z + 0.5 - pz, d2 = ex * ex + ez * ez;
            if (d2 >= r * r) continue;
            double t = 1 - Math.sqrt(d2) / r;
            double p = (hMin + unit(h4) * (hMax - hMin)) * t * t * Math.sqrt(t);
            // Ledges: the cone climbs in tiers of 200 blocks with a steep riser at the top of each.
            double q = p / 200, i = Math.floor(q), f = q - i;
            p = (i + (f <= 0.6 ? f / 0.6 * 0.6 : 0.6 + smooth((f - 0.6) / 0.4) * 0.4)) * 200;
            best = Math.max(best, p);
        }
        return best;
    }

    // Blocks -------------------------------------------------------------------------------------

    /**
     * The material at a height inside a run.
     *
     * @param top    the run's top Y
     * @param bottom the run's bottom Y
     */
    public static int material(int kind, int y, int top, int bottom) {
        if (kind == GROUND && y <= MIN_Y) return BEDROCK;
        int depth = top - y;
        if (kind == ISLAND) {
            boolean cold = top > 7000;
            if (depth == 0) return cold ? SNOW : GRASS;
            if (depth < 4) return cold ? PACKED_ICE : DIRT;
            return y - bottom < 3 && ((x31(y) & 3) == 0) ? ANDESITE : STONE;
        }
        if (depth == 0) return top < 2500 ? GRASS : top < 6500 ? STONE : SNOW;
        if (depth < 4) return top < 2500 ? DIRT : top < 6500 ? STONE : PACKED_ICE;
        if (y < -4000) return ((y >> 6) & 7) == 0 ? BLACKSTONE : DEEPSLATE;
        return switch (x31(y >> 5) & 7) {
            case 0 -> ANDESITE;
            case 1 -> TUFF;
            case 2 -> y > 5000 ? CALCITE : STONE;
            default -> STONE;
        };
    }

    /** Which biome a run's top stands in: 0 grassland, 1 bare rock, 2 snow. */
    public static int biome(int kind, int top) {
        if (kind == ISLAND) return top > 7000 ? 2 : 0;
        return top < 2500 ? 0 : top < 6500 ? 1 : 2;
    }

    private static int x31(int v) { return (int) (mix(v * 0x9E3779B97F4A7C15L) >>> 40); }

    // Noise --------------------------------------------------------------------------------------

    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    private static long mix(long h) {
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L;
        return h ^ (h >>> 33);
    }

    private long hash(long salt, int a, int b) {
        long h = mix(seed ^ (salt * 0x9E3779B97F4A7C15L));
        h = mix(h ^ (a * 0xC2B2AE3D27D4EB4FL));
        return mix(h ^ (b * 0x165667B19E3779F9L));
    }

    private static double unit(long h) { return (h >>> 11) * 0x1.0p-53; }

    /** Value noise in [0, 1) on an integer lattice of {@code size} blocks. */
    private double noise(long salt, int x, int z, int size) {
        int cx = Math.floorDiv(x, size), cz = Math.floorDiv(z, size);
        double u = smooth((x - cx * size) / (double) size), v = smooth((z - cz * size) / (double) size);
        double a = unit(hash(salt, cx, cz)), b = unit(hash(salt, cx + 1, cz)), c = unit(hash(salt, cx, cz + 1)), d = unit(hash(salt, cx + 1, cz + 1));
        double top = a + (b - a) * u, bottom = c + (d - c) * u;
        return top + (bottom - top) * v;
    }

    /** Octaves of {@link #noise}, each half the size and half the weight, normalised to [0, 1). */
    private double fbm(long salt, int x, int z, int size, int octaves) {
        double sum = 0, weight = 0, amplitude = 1;
        for (int o = 0; o < octaves; o++) {
            sum += amplitude * noise(salt * 31 + o, x, z, Math.max(4, size >> o));
            weight += amplitude;
            amplitude *= 0.5;
        }
        return sum / weight;
    }
}
