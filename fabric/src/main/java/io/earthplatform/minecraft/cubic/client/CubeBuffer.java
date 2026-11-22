package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import javax.annotation.Nullable;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.system.MemoryUtil;

/**
 * One layer of a near cube's mesh on the GPU, with its own vertex buffer and, for sorted translucent quads, its own
 * index buffer.
 *
 * <p>Before 26.2 this held its own vertex array and bound its index buffer again before every draw: a shader pack
 * restoring "the last vertex buffer" could otherwise swap a shared array's indices, which on the Mac drew every face as
 * one triangle of two. Since 26.2 the game's render pass binds the vertex and index buffers for each draw itself, so a
 * mesh is two Blaze3D buffers drawn through the pass ({@link #draw}), unsorted quads with the game's shared quad indices.
 */
final class CubeBuffer implements AutoCloseable {
    @Nullable private GpuBuffer vertices, indices;
    private int indexCount;
    private IndexType indexType = IndexType.INT;
    private boolean closed;

    CubeBuffer() {
        RenderSystem.assertOnRenderThread();
    }

    /** Uploads a mesh (and closes it). */
    void upload(MeshData mesh) {
        try (mesh) {
            if (closed) return;
            RenderSystem.assertOnRenderThread();
            MeshData.DrawState state = mesh.drawState();
            if (state.primitiveTopology() != PrimitiveTopology.QUADS) throw new IllegalArgumentException("Cube buffers hold quads, not " + state.primitiveTopology());
            release();
            ByteBuffer vertexData = mesh.vertexBuffer();
            indexCount = state.indexCount();
            if (vertexData == null || indexCount == 0) { indexCount = 0; return; }
            vertices = RenderSystem.getDevice().createBuffer(() -> "Earth cube vertices", GpuBuffer.USAGE_VERTEX, vertexData);
            ByteBuffer own = mesh.indexBuffer();
            if (own != null) {
                indices = RenderSystem.getDevice().createBuffer(() -> "Earth cube indices", GpuBuffer.USAGE_INDEX, own);
                indexType = state.indexType();
            }
        }
    }

    /** Indices the draw needs from the shared quad indices, 0 when it has sorted ones of its own. */
    int quadIndices() { return indices == null ? indexCount : 0; }

    /**
     * Draws the mesh in a render pass whose pipeline, uniforms and textures are set; {@code quads} are the game's shared
     * quad indices, of the {@code quadType}, long enough for {@link #quadIndices}.
     */
    boolean draw(RenderPass pass, GpuBuffer quads, IndexType quadType) {
        if (closed || vertices == null || indexCount == 0) return false;
        pass.setVertexBuffer(0, vertices.slice());
        if (indices != null) pass.setIndexBuffer(indices, indexType);
        else pass.setIndexBuffer(quads, quadType);
        pass.drawIndexed(0, 0, indexCount, 1, 0);
        return true;
    }

    /** Leaves no vertex array bound, for far water's raw OpenGL draws ({@link FarWaterBuffers}). */
    static void unbind() {
        GlStateManager._glBindVertexArray(0);
    }

    private void release() {
        if (vertices != null) vertices.close();
        if (indices != null) indices.close();
        vertices = indices = null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        release();
    }

    /** Indices 0 1 2 2 3 0 for every quad, as 32-bit ints, shared by far water ({@link FarWaterBuffers}). */
    static final class QuadIndices {
        static int name;
        static int capacity;

        static void ensure(int count) {
            if (name == 0) name = GlStateManager._glGenBuffers();
            GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, name);
            if (count <= capacity) return;
            int quads = Math.max(1 << 14, Integer.highestOneBit(Math.max(1, count / 6 - 1)) << 1);
            IntBuffer data = MemoryUtil.memAllocInt(quads * 6);
            try {
                for (int quad = 0; quad < quads; quad++) {
                    int v = quad * 4;
                    data.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v);
                }
                data.flip();
                GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, data, GL15C.GL_STATIC_DRAW);
            }
            finally {
                MemoryUtil.memFree(data);
            }
            capacity = quads * 6;
        }
    }
}
