package io.earthplatform.minecraft.cubic.play.client;

import java.util.Locale;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;

/**
 * The layered clouds: three banks at fixed heights (see {@link CloudField}) with thickness and
 * their own winds, ray marched in one full-screen pass and stopped at the scene's depth (see
 * {@link CloudDepth}), so mountains rise through them. They curve with far terrain (see
 * {@code earthplatform.far.curvature}). Weather fronts (see {@link CloudWeather})
 * thin and thicken them over time. Without a shader pack only. Render thread only.
 */
final class CloudVolume {
    private CloudVolume() {}

    /** Ray samples per bank for {@code earthplatform.clouds.quality}. */
    static int steps(String quality) {
        return switch (quality.toLowerCase(Locale.ROOT)) {
            case "balanced" -> 16;
            case "cinematic" -> 32;
            default -> 24;
        };
    }

    /** The most the clouds may cover, from {@code earthplatform.clouds.opacity}, kept within 0.2 to 0.98. */
    static float opacity(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? (float) Math.clamp(parsed, .2, .98) : .72f;
        }
        catch (NumberFormatException | NullPointerException invalid) { return .72f; }
    }

    /**
     * Draws the clouds for this frame; false when it can't, and the plain deck draws instead.
     *
     * TODO(26.2): the march ran the {@code earthplatform_weather} core shader through ShaderInstance, with a
     * full-screen quad drawn by Tesselator and BufferUploader, its coverage textures, a 3D noise texture and the
     * scene's depth bound by hand. 26.x replaced all of that with render pipelines and render passes, so until the
     * pass is rebuilt on them the layered clouds are off (false) and the plain deck is tried instead.
     */
    static boolean draw(ClientLevel level, Matrix4f view, Matrix4f projection, float partialTick, double camX, double camY, double camZ, int height) {
        return false;
    }
}
