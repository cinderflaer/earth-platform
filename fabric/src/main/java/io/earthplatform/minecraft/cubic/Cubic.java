package io.earthplatform.minecraft.cubic;

import io.earthplatform.minecraft.EarthPlatformMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/** Identity of the cubic dimension. */
public final class Cubic {
    public static final ResourceKey<Level> LEVEL =
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "cubic"));
    /** The tall cubic Nether: a second carrier dimension, reached by portals from {@link #LEVEL} with the height mapped. */
    public static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "cubic_nether"));
    /** Vertical cube radius kept loaded and sent around each player. */
    public static final int VERTICAL_RADIUS = 6;
    /** Horizontal cube radius cap, applied on top of the server view distance. */
    public static final int MAX_HORIZONTAL_RADIUS = 12;

    /** -Dearthplatform.cubic.trace=true logs cube traffic, for engine debugging. */
    public static final boolean TRACE = Boolean.getBoolean("earthplatform.cubic.trace");

    private Cubic() {}

    /** Any cubic carrier: the Earth or its Nether. */
    public static boolean isCubic(ResourceKey<Level> key) { return LEVEL.equals(key) || NETHER.equals(key); }
    /** The Earth itself, with its sky, clouds, currents and wind. */
    public static boolean isEarth(ResourceKey<Level> key) { return LEVEL.equals(key); }
    public static boolean isNether(ResourceKey<Level> key) { return NETHER.equals(key); }
}
