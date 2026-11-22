package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import io.earthplatform.minecraft.platform.Loader;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tells an active shader pack which block each vertex belongs to, as Iris does for its own chunk
 * meshes, so packs can pick out water, leaves, lights and the rest. While a pack is in use Iris
 * widens block-format buffers to its terrain format; this fills in the block for each quad.
 * Does nothing without Iris or with no pack in use.
 */
final class ShaderBlocks {
    static final boolean IRIS = Loader.INSTANCE.isModLoaded("iris");

    private ShaderBlocks() {}

    /** The pack's block ids, or null when no pack is in use. Taken once per mesh, on the client thread. */
    @Nullable static Object2IntMap<BlockState> ids() { return IRIS ? IrisIds.ids() : null; }

    /** True while a shader pack is in use. */
    static boolean packInUse() { return IRIS && net.irisshaders.iris.Iris.isPackInUseQuick(); }

    /** The pack's Voxy fragment code for the current dimension, or null when it has none (see {@link FarPackPatch}). */
    @Nullable static FarShaders.PackPatch voxyPatch() { return IRIS ? FarPackPatch.current() : null; }

    /** True while a shader pack draws its shadow map, when only shadow casters are wanted. */
    static boolean shadowPass() {
        return IRIS && net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass();
    }

    /**
     * Iris's own frustum for terrain in the shadow map this pass, or null without Iris. With the pack's leave (its
     * {@code shadow.culling}), Iris culls there what can cast no shadow into the view; with a pack that needs every
     * caster (one that voxelizes the world, say) it culls only by the map's box and distance, as for vanilla sections.
     */
    @Nullable static net.minecraft.client.renderer.culling.Frustum shadowFrustum() {
        return IRIS ? IrisShadow.frustum() : null;
    }

    /** Whether {@link #shadowFrustum}'s frustum is the one Iris culls by what can cast a shadow into the view: the pack lets it. */
    static boolean shadowCulls(net.minecraft.client.renderer.culling.Frustum frustum) { return IRIS && IrisShadow.culls(frustum); }

    /** Only loaded when Iris is installed. */
    private static final class IrisShadow {
        static boolean culls(net.minecraft.client.renderer.culling.Frustum frustum) {
            return frustum instanceof net.irisshaders.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum;
        }

        @Nullable static net.minecraft.client.renderer.culling.Frustum frustum() {
            return net.irisshaders.iris.shadows.ShadowRenderer.FRUSTUM;
        }
    }

    /**
     * Binds the framebuffer a shader pack draws its terrain into (before its translucent layer) or its water into
     * (after), as applying the game's terrain shader did until 26.x; false when there is none to find, and the
     * caller draws into whatever is bound. Under a pack only.
     */
    static boolean bindPackTarget(boolean translucent) {
        if (!IRIS) return false;
        try { return IrisTarget.bind(translucent); }
        catch (Throwable failure) { return false; }
    }

    /**
     * Narrows the bound pack framebuffer to its first colour attachment, which a plain shader writes alone; the
     * other outputs of such a shader are undefined. Returns the draw buffers to give back to {@link #restoreDrawBuffers}.
     */
    static int[] firstDrawBufferOnly() {
        int[] before = new int[8];
        for (int i = 0; i < before.length; i++) before[i] = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL20C.GL_DRAW_BUFFER0 + i);
        org.lwjgl.opengl.GL20C.glDrawBuffers(org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0);
        return before;
    }

    /** Puts back the draw buffers {@link #firstDrawBufferOnly} narrowed (the framebuffer must still be bound). */
    static void restoreDrawBuffers(int[] buffers) {
        int count = buffers.length;
        while (count > 1 && buffers[count - 1] == org.lwjgl.opengl.GL11C.GL_NONE) count--;
        org.lwjgl.opengl.GL20C.glDrawBuffers(java.util.Arrays.copyOf(buffers, count));
    }

    /** Only loaded when Iris is installed. */
    private static final class IrisTarget {
        private static java.lang.reflect.Field before, after;
        private static boolean searched;

        static boolean bind(boolean translucent) throws ReflectiveOperationException {
            if (!(net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable()
                    instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline pipeline)) return false;
            net.irisshaders.iris.pipeline.programs.ShaderMap map = pipeline.getShaderMap();
            if (map == null) return false;
            net.irisshaders.iris.pipeline.programs.ShaderKey[] keys = translucent
                    ? new net.irisshaders.iris.pipeline.programs.ShaderKey[] {net.irisshaders.iris.pipeline.programs.ShaderKey.TERRAIN_TRANSLUCENT,
                            net.irisshaders.iris.pipeline.programs.ShaderKey.SODIUM_TERRAIN_TRANSLUCENT}
                    : new net.irisshaders.iris.pipeline.programs.ShaderKey[] {net.irisshaders.iris.pipeline.programs.ShaderKey.TERRAIN_SOLID,
                            net.irisshaders.iris.pipeline.programs.ShaderKey.SODIUM_TERRAIN_SOLID};
            if (!searched) {
                searched = true;
                before = field("writingToBeforeTranslucent");
                after = field("writingToAfterTranslucent");
            }
            java.lang.reflect.Field wanted = translucent ? after : before;
            if (wanted == null) return false;
            for (net.irisshaders.iris.pipeline.programs.ShaderKey key : keys) {
                Object program;
                try { program = map.getShader(key); }
                catch (RuntimeException failure) { continue; }
                if (!(program instanceof net.irisshaders.iris.pipeline.programs.ExtendedShader)) continue;
                if (!(wanted.get(program) instanceof net.irisshaders.iris.gl.framebuffer.GlFramebuffer framebuffer)) continue;
                framebuffer.bind();
                return true;
            }
            return false;
        }

        private static java.lang.reflect.Field field(String name) {
            try {
                java.lang.reflect.Field field = net.irisshaders.iris.pipeline.programs.ExtendedShader.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException failure) { return null; }
        }
    }

    /** Marks the quads written next as the given block's, until {@link #end}. */
    static void begin(BufferBuilder builder, @Nullable Object2IntMap<BlockState> ids, BlockState state, boolean fluid,
                      int x, int y, int z) {
        if (ids != null) IrisIds.begin(builder, ids, state, fluid, x, y, z);
    }

    static void end(BufferBuilder builder, @Nullable Object2IntMap<BlockState> ids) {
        if (ids != null) IrisIds.end(builder);
    }

    /** Only loaded when Iris is installed. */
    private static final class IrisIds {
        @Nullable static Object2IntMap<BlockState> ids() {
            if (!net.irisshaders.iris.Iris.isPackInUseQuick()) return null;
            return net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getBlockStateIds();
        }

        static void begin(BufferBuilder builder, Object2IntMap<BlockState> ids, BlockState state, boolean fluid, int x, int y, int z) {
            if (!(builder instanceof net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder sensitive)) return;
            byte type = (byte) (fluid ? net.irisshaders.iris.vertices.ExtendedDataHelper.FLUID_RENDER_TYPE
                    : net.irisshaders.iris.vertices.ExtendedDataHelper.BLOCK_RENDER_TYPE);
            sensitive.beginBlock(ids.getOrDefault(state, -1), type, (byte) state.getLightEmission(), x, y, z);
        }

        static void end(BufferBuilder builder) {
            if (builder instanceof net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder sensitive) sensitive.endBlock();
        }
    }
}
