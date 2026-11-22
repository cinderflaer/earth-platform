package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.OutsideMapException;
import io.earthplatform.geography.globe.MercatorGrid;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Where the map ends and the Farlands begin, and the Farlands' terrain: deterministic, sane and precise out to the world's hard cap. */
class FarlandsTerrainTest {
    private static final MercatorGrid GRID = new MercatorGrid(1.0);
    private static final double HALF = GRID.worldWidthBlocks() / 2;
    private static final int FAR = 29_999_000;

    private static int[] layers(FarlandsTerrain terrain, int x, int z) {
        int[] buffer = new int[FarlandsTerrain.MAX_LAYERS * FarlandsTerrain.STRIDE];
        int count = terrain.layers(0, x, z, buffer);
        return Arrays.copyOf(buffer, count * FarlandsTerrain.STRIDE);
    }

    private static void assertSane(int[] layers) {
        assertTrue(layers.length >= FarlandsTerrain.STRIDE);
        assertEquals(FarlandsTerrain.MIN_Y, layers[0]);
        assertEquals(FarlandsTerrain.GROUND, layers[2]);
        int previousTop = Integer.MIN_VALUE;
        for (int i = 0; i < layers.length; i += FarlandsTerrain.STRIDE) {
            assertTrue(layers[i] <= layers[i + 1], "run is not empty");
            assertTrue(layers[i] >= FarlandsTerrain.MIN_Y && layers[i + 1] <= FarlandsTerrain.MAX_Y, "run is inside the world");
            if (i > 0) assertTrue(layers[i] > previousTop + 1, "runs neither touch nor overlap");
            previousTop = layers[i + 1];
        }
    }

    // The decision -------------------------------------------------------------------------------

    @Test void theMapEndsJustBeforeItsEdge() {
        assertTrue(FarlandsTerrain.inside(HALF, 0));
        assertTrue(FarlandsTerrain.inside(HALF, HALF - FarlandsTerrain.EDGE_MARGIN));
        assertTrue(FarlandsTerrain.inside(HALF, -(HALF - FarlandsTerrain.EDGE_MARGIN)));
        assertFalse(FarlandsTerrain.inside(HALF, HALF - FarlandsTerrain.EDGE_MARGIN + 1));
        assertFalse(FarlandsTerrain.inside(HALF, HALF + 1));
        assertFalse(FarlandsTerrain.inside(HALF, -HALF - 1));
        assertFalse(FarlandsTerrain.inside(HALF, 29_999_999));
        assertFalse(FarlandsTerrain.inside(HALF, -29_999_999));
    }

    @Test void columnsReachingPastTheEdgeBelongToTheFarlands() {
        int edgeCube = (int) Math.floor(HALF / 16);
        assertTrue(FarlandsTerrain.cubeColumnInside(HALF, 0));
        assertTrue(FarlandsTerrain.cubeColumnInside(HALF, edgeCube - 3));
        assertFalse(FarlandsTerrain.cubeColumnInside(HALF, edgeCube));
        assertFalse(FarlandsTerrain.cubeColumnInside(HALF, edgeCube + 1000));
        assertFalse(FarlandsTerrain.cubeColumnInside(HALF, -edgeCube - 1));
        assertFalse(FarlandsTerrain.cubeColumnInside(HALF, 1_800_000));
        assertFalse(FarlandsTerrain.cubeColumnInside(HALF, -1_800_000));
        // Once outside, always outside going outward; every block an inside cube column reads stays within the margin.
        boolean outside = false;
        for (int cz = edgeCube - 50; cz < edgeCube + 50; cz++) {
            boolean in = FarlandsTerrain.cubeColumnInside(HALF, cz);
            assertFalse(outside && in, "inside again beyond the edge at " + cz);
            if (in) assertTrue(cz * 16L + 17 <= HALF - FarlandsTerrain.EDGE_MARGIN);
            outside |= !in;
        }
        assertTrue(outside);
    }

    @Test void farColumnsAreWholeOrNot() {
        for (int level = 1; level <= 8; level++) {
            long width = 16L << level;
            int edgeColumn = (int) Math.floor(HALF / width);
            assertTrue(FarlandsTerrain.farColumnInside(HALF, level, 0), "level " + level);
            assertFalse(FarlandsTerrain.farColumnInside(HALF, level, edgeColumn), "level " + level);
            assertFalse(FarlandsTerrain.farColumnInside(HALF, level, -edgeColumn - 1), "level " + level);
            assertFalse(FarlandsTerrain.farColumnInside(HALF, level, (int) (29_000_000 / width)), "level " + level);
        }
    }

    @Test void theGridTellsOutsideFromMissing() {
        assertTrue(GRID.covers(0));
        assertTrue(GRID.covers(HALF));
        assertFalse(GRID.covers(HALF + 1));
        assertFalse(GRID.covers(-29_999_000));
        OutsideMapException outside = assertThrows(OutsideMapException.class, () -> GRID.geographic(0, HALF + 1));
        assertTrue(outside instanceof MissingTerrainException, "old catches still see it");
        assertThrows(OutsideMapException.class, () -> GRID.geographic(5, -29_999_000));
        // Clamped: the nearest edge, never a throw, X still wrapping.
        assertEquals(GRID.geographic(7, HALF).latitude(), GRID.geographicClamped(7, 29_999_000).latitude(), 1e-12);
        assertEquals(GRID.geographic(7, -HALF).latitude(), GRID.geographicClamped(7, -29_999_000).latitude(), 1e-12);
        assertEquals(GRID.geographic(7, 100).latitude(), GRID.geographicClamped(7, 100).latitude(), 0);
    }

    // The terrain --------------------------------------------------------------------------------

    @Test void sameSeedSameColumnsAndDifferentSeedsDiffer() {
        FarlandsTerrain a = new FarlandsTerrain(1234, HALF, null), b = new FarlandsTerrain(1234, HALF, null), c = new FarlandsTerrain(1235, HALF, null);
        int differing = 0;
        for (int i = 0; i < 400; i++) {
            int x = i * 977 - 100_000, z = (int) HALF + 6000 + (i * 313) % 9000;
            assertArrayEquals(layers(a, x, z), layers(b, x, z));
            assertArrayEquals(layers(a, x, -z), layers(b, x, -z));
            if (!Arrays.equals(layers(a, x, z), layers(c, x, z))) differing++;
        }
        assertTrue(differing > 300, "another seed is another land, differing in " + differing);
        assertEquals(layers(a, 5, (int) HALF + 9000)[1], a.groundTop(0, 5, (int) HALF + 9000), "groundTop is the ground run's top");
    }

    @Test void columnsStayWellFormedAtTheCap() {
        FarlandsTerrain terrain = new FarlandsTerrain(42, HALF, null);
        int[] xs = {-29_999_999, -FAR, -1, 0, 1, FAR, 29_999_999}, zs = {-29_999_999, -FAR, -25_000_000, 25_000_000, FAR, 29_999_999};
        for (int x : xs) for (int z : zs) assertSane(layers(terrain, x, z));
    }

    @Test void terrainKeepsItsPrecisionAtTheCap() {
        FarlandsTerrain terrain = new FarlandsTerrain(99, HALF, null);
        for (int z : new int[]{FAR, -FAR}) {
            for (int x0 : new int[]{FAR, -FAR}) {
                int[] heights = new int[4096];
                java.util.Set<Integer> distinct = new java.util.HashSet<>();
                for (int i = 0; i < heights.length; i++) { heights[i] = terrain.groundTop(0, x0 + i, z); distinct.add(heights[i]); }
                assertTrue(distinct.size() > 100, "the land varies: " + distinct.size() + " heights");
                // Lattice noise on exact integers is continuous: a rounding error in the coordinates would scatter it.
                int[] steps = new int[heights.length - 1];
                for (int i = 0; i < steps.length; i++) steps[i] = Math.abs(heights[i + 1] - heights[i]);
                Arrays.sort(steps);
                assertTrue(steps[steps.length / 2] <= 25, "median step " + steps[steps.length / 2]);
                // And the same as asking again.
                for (int i = 0; i < heights.length; i += 97) assertEquals(heights[i], terrain.groundTop(0, x0 + i, z));
            }
        }
    }

    @Test void islandsFloatOverTheOpenFarlandsButNotTheEdge() {
        FarlandsTerrain terrain = new FarlandsTerrain(7, HALF, null);
        int floating = 0, tall = 0;
        for (int i = 0; i < 160; i++) for (int j = 0; j < 160; j++) {
            int[] layers = layers(terrain, i * 97, (int) HALF + 4000 + j * 61);
            assertSane(layers);
            if (layers.length > FarlandsTerrain.STRIDE) floating++;
            if (layers[1] > 8000) tall++;
        }
        assertTrue(floating > 50, "islands: " + floating);
        assertTrue(tall > 20, "spires: " + tall);
        for (int i = 0; i < 300; i++) for (int dz = 0; dz < 1000; dz += 40)
            assertEquals(FarlandsTerrain.STRIDE, layers(terrain, i * 331, (int) HALF + dz).length, "no islands before the terrain has grown");
    }

    @Test void theFarlandsGrowOutOfTheEdgeGroundWithoutASeam() {
        int[] reads = {0};
        FarlandsTerrain terrain = new FarlandsTerrain(3, HALF, (level, x, z) -> {
            reads[0]++;
            assertTrue(FarlandsTerrain.inside(HALF, z), "reads the maps own rows");
            return 123 + (x & 7);
        });
        int row = (int) Math.floor(HALF);
        for (int x = -50; x < 50; x++) {
            // On and short of the edge it is the edge's ground; just past it still within a block or so of it.
            assertEquals(123 + (x & 7), terrain.groundTop(0, x, row - 100));
            assertEquals(123 + (x & 7), terrain.groundTop(0, x, -row + 100));
            assertTrue(Math.abs(terrain.groundTop(0, x, row + 1) - (123 + (x & 7))) <= 2);
            assertTrue(Math.abs(terrain.groundTop(0, x, -row - 1) - (123 + (x & 7))) <= 2);
            // The step across seven blocks anywhere in the blend is small against the terrain's size.
            int previous = terrain.groundTop(0, x, row);
            for (int dz = 7; dz < FarlandsTerrain.BLEND; dz += 7) {
                int h = terrain.groundTop(0, x, row + dz);
                assertTrue(Math.abs(h - previous) < 700, "step " + (h - previous) + " at " + dz);
                previous = h;
            }
        }
        // Past the blend the edge no longer matters.
        FarlandsTerrain other = new FarlandsTerrain(3, HALF, (level, x, z) -> -9000);
        int beyond = row + FarlandsTerrain.BLEND + 10;
        for (int x = 0; x < 50; x++) assertEquals(terrain.groundTop(0, x, beyond), other.groundTop(0, x, beyond));
        assertTrue(reads[0] > 0);
    }

    @Test void materialsMakeASurfaceOnEachRun() {
        assertEquals(FarlandsTerrain.BEDROCK, FarlandsTerrain.material(FarlandsTerrain.GROUND, FarlandsTerrain.MIN_Y, 100, FarlandsTerrain.MIN_Y));
        assertEquals(FarlandsTerrain.GRASS, FarlandsTerrain.material(FarlandsTerrain.GROUND, 100, 100, FarlandsTerrain.MIN_Y));
        assertEquals(FarlandsTerrain.SNOW, FarlandsTerrain.material(FarlandsTerrain.GROUND, 9000, 9000, FarlandsTerrain.MIN_Y));
        assertEquals(FarlandsTerrain.GRASS, FarlandsTerrain.material(FarlandsTerrain.ISLAND, 500, 500, 100));
        assertNotEquals(FarlandsTerrain.AIR, FarlandsTerrain.material(FarlandsTerrain.GROUND, -9000, 100, FarlandsTerrain.MIN_Y));
    }
}
