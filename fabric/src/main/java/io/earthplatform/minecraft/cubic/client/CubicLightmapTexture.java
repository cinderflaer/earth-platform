package io.earthplatform.minecraft.cubic.client;

import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * The game's 16x16 lightmap, uploaded in turn to three textures instead of always to one.
 *
 * <p>The game rewrote the lightmap every tick. Written over while earlier frames still drew with it, the one texture
 * made some drivers wait for the GPU to finish those frames first: on Apple's OpenGL the upload measured about 17 ms
 * of a 21 ms frame. Writing the next of three textures ({@link LightmapRing}) left the ones in use alone.
 *
 * <p>Not applicable in 26.2: the game now renders its lightmap on the GPU ({@code Lightmap}, a render target drawn from
 * a uniform buffer that is already a ring buffer) and never uploads a 16x16 {@link DynamicTexture} from the CPU, so there is
 * no upload to spread over three textures and nothing to hook. The ring is therefore off ({@link #ENABLED} is false) and
 * this class only remains so callers still compile; it behaves as a plain dynamic texture, which fails soft.
 * -Dearthplatform.cubic.lightmapRing has no effect.
 */
public final class CubicLightmapTexture extends DynamicTexture {
    /** Always false since 26.2: see the class comment. */
    public static final boolean ENABLED = false;

    public CubicLightmapTexture(int width, int height, boolean clear) {
        super("Earth lightmap", width, height, clear);
    }
}
