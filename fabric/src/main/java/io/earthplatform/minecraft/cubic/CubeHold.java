package io.earthplatform.minecraft.cubic;

/**
 * When a player in a cubic level is waiting for the cubes at their feet (the client holds them still meanwhile, see
 * CubicClient), the server must not hurt them either: nothing around them is there to read yet, and a rejoined
 * world puts them at their saved spot in the air of cubes that load in a moment.
 */
public final class CubeHold {
    private CubeHold() {}

    /**
     * Whether damage from a source must be ignored for a player waiting on cubes.
     *
     * @param waiting whether the cubes at the feet or just below were not sent to the player yet
     * @param exempt a player gliding high above terrain, in spectator mode, or otherwise never held
     * @param kill the damage is the /kill command's, or the void's below the deepest cube
     */
    public static boolean shields(boolean waiting, boolean exempt, boolean kill) {
        return waiting && !exempt && !kill;
    }

    /** Whether a position lies below the deepest cube's floor, where the void damages as in vanilla. */
    public static boolean belowWorld(double y) { return y <= CubeWorld.MIN_Y - 64; }
}
