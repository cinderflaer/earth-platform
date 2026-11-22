package io.earthplatform.minecraft.cubic.portal;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;

/**
 * The linear height mapping between the Nether and the 32768-tall Earth, free of game classes:
 * earthY = SEA + (netherY - 32) * factor, so the Nether's lava sea (32) is the Earth's sea level.
 */
public final class NetherHeight {
    /** The Nether's lava sea level, the Nether height anchored to the Earth's sea level. */
    public static final int ANCHOR_Y = 32;
    /** Lowest and highest Nether Y a mapped portal may be sought or built at. */
    public static final int NETHER_MIN_Y = 1, NETHER_MAX_Y = 120;

    /** Lowest and highest Nether Y of the tall cubic Nether a portal may be sought or built at (inside its solid ends). */
    public static final int TALL_MIN_Y = -2032, TALL_MAX_Y = 2031;

    private NetherHeight() {}

    /** The Nether Y of the tall cubic Nether for an Earth Y, the inverse of {@link #toEarthY} clamped to its generated range. */
    public static double toTallNetherY(double earthY, double factor) {
        double y = ANCHOR_Y + (earthY - EarthGenerator.SEA) / factor;
        return Math.max(TALL_MIN_Y, Math.min(TALL_MAX_Y, y));
    }

    public static double toEarthY(double netherY, double factor) {
        double y = EarthGenerator.SEA + (netherY - ANCHOR_Y) * factor;
        return Math.max(CubeWorld.MIN_Y, Math.min(CubeWorld.MAX_Y, y));
    }

    public static double toNetherY(double earthY, double factor) {
        double y = ANCHOR_Y + (earthY - EarthGenerator.SEA) / factor;
        return Math.max(NETHER_MIN_Y, Math.min(NETHER_MAX_Y, y));
    }
}
