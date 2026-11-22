package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.portal.NetherHeight;
import org.junit.jupiter.api.Test;

class NetherHeightTest {
    private static final double F = 8;

    private static double up(double y) { return NetherHeight.toEarthY(y, F); }
    private static double down(double y) { return NetherHeight.toNetherY(y, F); }

    @Test void anchorsLavaSeaToEarthSea() {
        assertEquals(EarthGenerator.SEA, up(NetherHeight.ANCHOR_Y), 1e-9);
        assertEquals(NetherHeight.ANCHOR_Y, down(EarthGenerator.SEA), 1e-9);
    }

    @Test void roundTripsInsideTheNetherBand() {
        for (double y = NetherHeight.NETHER_MIN_Y; y <= NetherHeight.NETHER_MAX_Y; y += 0.5) {
            assertEquals(y, down(up(y)), 1e-6, "y=" + y);
        }
    }

    @Test void monotonic() {
        double lastUp = Double.NEGATIVE_INFINITY, lastDown = Double.NEGATIVE_INFINITY;
        for (double y = -300; y <= 400; y += 1) {
            assertTrue(up(y) >= lastUp);
            assertTrue(down(y * 100) >= lastDown);
            lastUp = up(y); lastDown = down(y * 100);
        }
        assertTrue(up(33) > up(32));
    }

    @Test void clamped() {
        assertEquals(CubeWorld.MIN_Y, up(-1e9));
        assertEquals(CubeWorld.MAX_Y, up(1e9));
        assertEquals(NetherHeight.NETHER_MIN_Y, down(-1e9));
        assertEquals(NetherHeight.NETHER_MAX_Y, down(1e9));
    }

    @Test void customFactor() {
        assertEquals(EarthGenerator.SEA + 20, NetherHeight.toEarthY(34, 10), 1e-9);
        assertEquals(34, NetherHeight.toNetherY(EarthGenerator.SEA + 20, 10), 1e-9);
    }

    @Test void tallMappingReachesDeepAndHighNetherHeights() {
        double deep = NetherHeight.toTallNetherY(-10000, F), high = NetherHeight.toTallNetherY(10000, F);
        assertTrue(deep < -1000 && high > 1000);
        assertEquals(NetherHeight.TALL_MIN_Y, NetherHeight.toTallNetherY(-1e9, F), 1e-9);
        assertEquals(NetherHeight.TALL_MAX_Y, NetherHeight.toTallNetherY(1e9, F), 1e-9);
        for (double y = -1500; y <= 1500; y += 37) assertEquals(y, NetherHeight.toTallNetherY(up(y), F), 1e-6);
    }
}
