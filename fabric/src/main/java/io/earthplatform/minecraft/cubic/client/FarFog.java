package io.earthplatform.minecraft.cubic.client;

import net.minecraft.client.renderer.fog.FogData;

/**
 * The game's fog this frame, as far terrain and far water draw it. Since 26.x the game keeps its
 * fog in a uniform buffer the shaders read, no longer as values on the render system, so the fog
 * hook hands what it works out here ({@link #capture}, at the end of FogRenderer#setupFog, after
 * the mod has pushed terrain fog out to far terrain's reach).
 *
 * <p>The game now fogs by two distances at once: the environment's (water, blindness and so on)
 * by the distance from the eye, and the render distance's round the camera's column. Far terrain
 * has one fog: whichever of the two closes in first, with the distance it is measured by.
 * Until a fog is captured there is none. Render thread only.
 */
public final class FarFog {
    /** Fog by the distance from the eye, and by the larger of the horizontal and vertical distances (as the game's old FogShape). */
    static final int SPHERE = 0, CYLINDER = 1;

    private static float start = Float.MAX_VALUE, end = Float.MAX_VALUE;
    private static int shape = CYLINDER;
    private static final float[] colour = new float[4];

    private FarFog() {}

    /** Takes the fog the game worked out for this frame. */
    public static void capture(FogData fog) {
        boolean environmental = fog.environmentalEnd < fog.renderDistanceEnd;
        start = environmental ? fog.environmentalStart : fog.renderDistanceStart;
        end = environmental ? fog.environmentalEnd : fog.renderDistanceEnd;
        shape = environmental ? SPHERE : CYLINDER;
        colour[0] = fog.color.x();
        colour[1] = fog.color.y();
        colour[2] = fog.color.z();
        colour[3] = fog.color.w();
    }

    static float start() { return start; }

    static float end() { return end; }

    static int shape() { return shape; }

    /** The fog's colour, red, green, blue and how much of it shows; not to be changed. */
    static float[] colour() { return colour; }
}
