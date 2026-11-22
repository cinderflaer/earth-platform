package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CubeHoldTest {
    /** Rejoining a saved world: the player is in the air of cubes not sent yet, and nothing may hurt them. */
    @Test void aPlayerWaitingOnCubesTakesNoDamage() {
        assertTrue(CubeHold.shields(true, false, false));
    }

    @Test void aPlayerWithTheirCubesIsHurtAsUsual() {
        assertFalse(CubeHold.shields(false, false, false));
    }

    @Test void glidersAndSpectatorsAreNeverShielded() {
        assertFalse(CubeHold.shields(true, true, false));
    }

    @Test void killCommandAndTheDeepVoidStillKill() {
        assertFalse(CubeHold.shields(true, false, true));
        assertTrue(CubeHold.belowWorld(CubeWorld.MIN_Y - 64));
        assertFalse(CubeHold.belowWorld(1269));
    }
}
