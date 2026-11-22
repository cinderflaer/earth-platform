package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;

/**
 * What far terrain's raw OpenGL needs from the game since 26.x, where the game draws through
 * Blaze3D's devices and passes and no longer leaves its textures, samplers and target bound as
 * render state: the GL names behind the game's textures and samplers, and the game's main target
 * to draw into. Everything here is for the OpenGL backend; on another one the names are 0 and far
 * terrain draws nothing. Render thread only.
 */
final class FarGl {
    /** A framebuffer of the main target's colour and depth textures, made again when they change. */
    private static int framebuffer, framebufferColour, framebufferDepth;
    private static int previousDraw, previousRead;
    private static final int[] previousViewport = new int[4];

    private FarGl() {}

    /** The active texture unit as GL has it, GL_TEXTURE0 and up (GlStateManager no longer gives it out). */
    static int activeTexture() { return GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE); }

    /** The colour mask GlStateManager takes now: a bit each for red, green, blue and alpha. */
    static int colourMask(boolean red, boolean green, boolean blue, boolean alpha) {
        return (red ? 1 : 0) | (green ? 2 : 0) | (blue ? 4 : 0) | (alpha ? 8 : 0);
    }

    /** The GL name of a texture, or 0 for none or another backend's. */
    static int glId(@Nullable GpuTexture texture) {
        return texture instanceof GlTexture gl && !gl.isClosed() ? gl.glId() : 0;
    }

    /** The same for a texture view's texture. */
    static int glId(@Nullable GpuTextureView view) { return view == null ? 0 : glId(view.texture()); }

    /** The GL name of a sampler object, or 0 for none. */
    static int samplerId(@Nullable GpuSampler sampler) { return sampler instanceof GlSampler gl && !gl.isClosed() ? gl.getId() : 0; }

    /** The block atlas. */
    @Nullable static AbstractTexture blockAtlas() {
        return Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
    }

    /** The GL name of the block atlas, or 0. */
    static int blockAtlasId() {
        AbstractTexture atlas = blockAtlas();
        return atlas == null ? 0 : glId(atlas.getTexture());
    }

    /** The sampler the block atlas is drawn with, or 0. */
    static int blockAtlasSampler() {
        AbstractTexture atlas = blockAtlas();
        return atlas == null ? 0 : samplerId(atlas.getSampler());
    }

    /** The GL name of the level's lightmap (what the game bound as its shader texture 2), or 0. */
    static int lightmapId() { return glId(Minecraft.getInstance().gameRenderer.levelLightmap()); }

    /** The sampler the game reads the lightmap with: linear, clamped to its edge. */
    static int lightmapSampler() { return samplerId(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)); }

    /** The game's main target. */
    static RenderTarget mainTarget() { return Minecraft.getInstance().gameRenderer.mainRenderTarget(); }

    /**
     * Binds the game's main target to draw into, with its viewport, as the old render types' output
     * state did; false when it can't (another backend), and nothing should be drawn. {@link #endMain} puts back
     * the framebuffers and viewport bound before.
     */
    static boolean beginMain() {
        RenderTarget target = mainTarget();
        int colour = glId(target.getColorTexture()), depth = target.useDepth ? glId(target.getDepthTexture()) : 0;
        if (colour == 0) return false;
        previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, previousViewport);
        if (framebuffer == 0) framebuffer = GL30C.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        if (colour != framebufferColour || depth != framebufferDepth) {
            framebufferColour = colour;
            framebufferDepth = depth;
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
            GL30C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
        }
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            endMain();
            return false;
        }
        GlStateManager._viewport(0, 0, target.width, target.height);
        return true;
    }

    /** Puts back what {@link #beginMain} replaced. */
    static void endMain() {
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
        GlStateManager._viewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
    }

    /** Binds a sampler object to a unit; 0 leaves a texture's own filtering. */
    static void bindSampler(int unit, int sampler) { GL33C.glBindSampler(unit, sampler); }
}
