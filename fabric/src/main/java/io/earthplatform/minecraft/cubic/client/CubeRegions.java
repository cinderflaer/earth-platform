package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.minecraft.cubic.CubeKey;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Near cube meshes kept together by region, 8 cubes a side, so a layer is drawn with one call a buffer in place of one
 * a cube: every cube had its own vertex array and buffer, and a view of a few thousand cubes was that many binds,
 * uniform uploads and draws for each of the three opaque layers, and again for a shader pack's shadow map.
 *
 * <p>A region's layer holds its cubes' meshes in a few buffers of fixed size, each mesh a run of vertices (and, for the
 * sorted translucent layer, of indices) given out by {@link CubeRanges}. A buffer never grows and no mesh ever moves: a
 * full region gets another buffer. The vertices are placed in the region, not the cube ({@link #shift}), so one chunk
 * section uniform, the region's, serves every cube in it, and a multi-draw with base vertices draws those in view.
 *
 * <p>The same triangles in the same order as cube by cube, except that the opaque layers go region by region (nearest
 * first), each region's cubes nearest first; translucent ones keep their exact order back to front, drawn in runs of
 * cubes that share a buffer. {@code -Dearthplatform.cubes.regions=false} gives every cube its own buffer as before,
 * for comparing.
 *
 * <p>Since 26.2 the buffers are Blaze3D buffers and every draw goes through the game's render pass for the layer.
 */
final class CubeRegions {
    static final boolean ON = !"false".equals(System.getProperty("earthplatform.cubes.regions"));
    /** A region is 1 &lt;&lt; SHIFT cubes a side. */
    static final int SHIFT = 3, MASK = (1 << SHIFT) - 1;
    /** Vertices the first, second and later buffers of a region's layer hold; a larger mesh gets one its own size. */
    private static final int[] CAPACITY = {8192, 32768, 131072};

    /** One mesh's place in a buffer. */
    static final class Slice {
        private final Arena arena;
        private final int firstVertex, vertexCount, firstIndex, indexCount;
        private boolean freed;

        private Slice(Arena arena, int firstVertex, int vertexCount, int firstIndex, int indexCount) {
            this.arena = arena; this.firstVertex = firstVertex; this.vertexCount = vertexCount;
            this.firstIndex = firstIndex; this.indexCount = indexCount;
        }

        /** The region the mesh lies in, as a {@link CubeKey} of region coordinates ({@link #originX} and so on). */
        long region() { return arena.region.key; }

        /** Indices the draw needs from the shared quad indices, 0 when its buffer has sorted ones of its own. */
        int quadIndices() { return arena.sorted ? 0 : indexCount; }
    }

    /** The block a region's vertices are placed from. */
    static int originX(long region) { return CubeKey.x(region) << SHIFT + 4; }
    static int originY(long region) { return CubeKey.y(region) << SHIFT + 4; }
    static int originZ(long region) { return CubeKey.z(region) << SHIFT + 4; }

    private static final class Region {
        final long key;
        @SuppressWarnings("unchecked") final List<Arena>[] layers = new List[CubeMesher.LAYERS.size()];
        int arenas;

        Region(long key) { this.key = key; }
    }

    /** One buffer of a region's layer: its vertices and, where meshes bring sorted indices, those. */
    private static final class Arena {
        final Region region;
        final int layer;
        final VertexFormat format;
        final boolean sorted;
        final CubeRanges vertexSpace;
        @Nullable final CubeRanges indexSpace;
        @Nullable GpuBuffer vertices, indices;
        int live;
        /** Draws waiting: index count, base vertex and first index, three ints each. */
        int[] queue = new int[48];
        int queued;

        Arena(Region region, int layer, VertexFormat format, boolean sorted, int capacity) {
            this.region = region; this.layer = layer; this.format = format; this.sorted = sorted;
            vertexSpace = new CubeRanges(capacity);
            // Quads: six indices to four vertices.
            indexSpace = sorted ? new CubeRanges(capacity / 4 * 6) : null;
            vertices = RenderSystem.getDevice().createBuffer(() -> "Earth cube region vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, (long) capacity * format.getVertexSize());
            if (sorted) {
                indices = RenderSystem.getDevice().createBuffer(() -> "Earth cube region indices",
                        GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, (long) indexSpace.capacity() * Integer.BYTES);
            }
        }

        boolean open() { return vertices != null; }

        void close() {
            if (vertices != null) vertices.close();
            if (indices != null) indices.close();
            vertices = indices = null;
        }
    }

    private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();
    /** Buffers that held nothing when last looked at; still empty a look later, they go. */
    private final List<Arena> idle = new ArrayList<>();
    private int arenaCount;
    private long bytes, usedBytes;

    int regionCount() { return regions.size(); }
    int bufferCount() { return arenaCount; }
    /** Bytes of GPU memory the buffers take, used or not. */
    long bytes() { return bytes; }
    /** Bytes of them that hold meshes. */
    long usedBytes() { return usedBytes; }

    /**
     * Moves a mesh's vertices from their place in the cube to their place in its region; on the mesher's thread.
     * Whole blocks, so every coordinate stays as exact as it was.
     */
    static void shift(MeshData mesh, int cubeX, int cubeY, int cubeZ) {
        float dx = (cubeX & MASK) << 4, dy = (cubeY & MASK) << 4, dz = (cubeZ & MASK) << 4;
        if (dx == 0.0F && dy == 0.0F && dz == 0.0F) return;
        MeshData.DrawState state = mesh.drawState();
        ByteBuffer data = mesh.vertexBuffer();
        int stride = state.format().getVertexSize();
        long at = MemoryUtil.memAddress(data) + state.format().getElement(DefaultVertexFormat.POSITION_SEMANTIC_NAME).offset();
        for (int i = 0, count = state.vertexCount(); i < count; i++, at += stride) {
            MemoryUtil.memPutFloat(at, MemoryUtil.memGetFloat(at) + dx);
            MemoryUtil.memPutFloat(at + 4, MemoryUtil.memGetFloat(at + 4) + dy);
            MemoryUtil.memPutFloat(at + 8, MemoryUtil.memGetFloat(at + 8) + dz);
        }
    }

    /** Uploads a cube's mesh for a layer (and closes it); null when it holds nothing. Never while a render pass is open. */
    @Nullable Slice upload(long cube, int layer, MeshData mesh) {
        try (mesh) {
            RenderSystem.assertOnRenderThread();
            MeshData.DrawState state = mesh.drawState();
            if (state.primitiveTopology() != PrimitiveTopology.QUADS) throw new IllegalArgumentException("Cube buffers hold quads, not " + state.primitiveTopology());
            ByteBuffer vertexData = mesh.vertexBuffer(), own = mesh.indexBuffer();
            int vertexCount = state.vertexCount(), indexCount = state.indexCount();
            if (vertexCount == 0 || indexCount == 0) return null;
            long key = CubeKey.pack(CubeKey.x(cube) >> SHIFT, CubeKey.y(cube) >> SHIFT, CubeKey.z(cube) >> SHIFT);
            Region region = regions.get(key);
            if (region == null) regions.put(key, region = new Region(key));
            Slice slice = place(region, layer, state.format(), own != null, vertexCount, indexCount);
            Arena arena = slice.arena;
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            long vertexSize = arena.format.getVertexSize();
            encoder.writeToBuffer(arena.vertices.slice(slice.firstVertex * vertexSize, (long) vertexData.remaining()), vertexData);
            if (own != null) {
                long offset = (long) slice.firstIndex * Integer.BYTES;
                if (state.indexType() == IndexType.INT) {
                    encoder.writeToBuffer(arena.indices.slice(offset, (long) own.remaining()), own);
                } else {
                    // Every mesh's indices as ints, so a run of meshes draws in one call.
                    ByteBuffer wide = MemoryUtil.memAlloc(indexCount * Integer.BYTES);
                    try {
                        IntBuffer ints = wide.asIntBuffer();
                        long from = MemoryUtil.memAddress(own);
                        for (int i = 0; i < indexCount; i++) ints.put(i, MemoryUtil.memGetShort(from + i * 2L) & 0xFFFF);
                        encoder.writeToBuffer(arena.indices.slice(offset, (long) indexCount * Integer.BYTES), wide);
                    } finally {
                        MemoryUtil.memFree(wide);
                    }
                }
            }
            return slice;
        }
    }

    /** Room for a mesh in one of the region's buffers for the layer, in a new buffer when the others are full. */
    private Slice place(Region region, int layer, VertexFormat format, boolean sorted, int vertexCount, int indexCount) {
        List<Arena> arenas = region.layers[layer];
        if (arenas == null) arenas = region.layers[layer] = new ArrayList<>(2);
        int same = 0;
        for (Arena arena : arenas) {
            // After a shader pack comes or goes, meshes in the old vertex format live on in their own buffers a while.
            if (arena.sorted != sorted || !arena.format.equals(format)) continue;
            same++;
            Slice slice = take(arena, vertexCount, indexCount);
            if (slice != null) { usedBytes += size(slice); return slice; }
        }
        Arena arena = new Arena(region, layer, format, sorted, Math.max(CAPACITY[Math.min(same, CAPACITY.length - 1)], (vertexCount + 3) / 4 * 4));
        arenas.add(arena);
        region.arenas++;
        arenaCount++;
        bytes += size(arena);
        Slice slice = take(arena, vertexCount, indexCount);
        if (slice != null) usedBytes += size(slice);
        if (slice == null) throw new IllegalStateException("A new cube buffer has no room for " + vertexCount + " vertices");
        return slice;
    }

    private static long size(Arena arena) {
        return (long) arena.vertexSpace.capacity() * arena.format.getVertexSize()
                + (arena.indexSpace == null ? 0L : (long) arena.indexSpace.capacity() * Integer.BYTES);
    }

    private static long size(Slice slice) {
        return (long) slice.vertexCount * slice.arena.format.getVertexSize() + (slice.arena.indexSpace == null ? 0L : (long) slice.indexCount * Integer.BYTES);
    }

    @Nullable private static Slice take(Arena arena, int vertexCount, int indexCount) {
        int firstVertex = arena.vertexSpace.take(vertexCount);
        if (firstVertex < 0) return null;
        int firstIndex = 0;
        if (arena.indexSpace != null) {
            firstIndex = arena.indexSpace.take(indexCount);
            if (firstIndex < 0) { arena.vertexSpace.give(firstVertex, vertexCount); return null; }
        }
        arena.live++;
        return new Slice(arena, firstVertex, vertexCount, firstIndex, indexCount);
    }

    /** A mesh is no longer drawn: its room is free again. */
    void free(@Nullable Slice slice) {
        if (slice == null || slice.freed) return;
        slice.freed = true;
        Arena arena = slice.arena;
        if (!arena.open()) return;
        usedBytes -= size(slice);
        arena.vertexSpace.give(slice.firstVertex, slice.vertexCount);
        if (arena.indexSpace != null) arena.indexSpace.give(slice.firstIndex, slice.indexCount);
        arena.live--;
    }

    /**
     * Now and then: deletes the buffers that were empty at the last look and still are. Not at once, since a cube
     * meshed again frees its room a moment before taking it back.
     */
    void sweep() {
        for (Arena arena : idle) {
            if (arena.live != 0 || !arena.open()) continue;
            Region region = arena.region;
            region.layers[arena.layer].remove(arena);
            bytes -= size(arena);
            arena.close();
            arenaCount--;
            if (--region.arenas == 0) regions.remove(region.key);
        }
        idle.clear();
        for (Region region : regions.values()) {
            for (List<Arena> arenas : region.layers) {
                if (arenas == null) continue;
                for (Arena arena : arenas) if (arena.live == 0) idle.add(arena);
            }
        }
    }

    // Drawing ------------------------------------------------------------------------------------

    @Nullable private RenderPass pass;
    @Nullable private Long2ObjectMap<GpuBufferSlice> sections;
    @Nullable private GpuBuffer quads;
    private IndexType quadType = IndexType.INT;
    private boolean ordered;
    /** Buffers with draws waiting, in the order first drawn from; in order, only ever the last one. */
    private final List<Arena> waiting = new ArrayList<>();
    /** The region the chunk section uniform is set for. */
    @Nullable private Region placed;
    private int calls;
    @Nullable private IntBuffer counts, bases;
    @Nullable private PointerBuffer offsets;

    /**
     * Starts drawing a layer in a render pass with its pipeline, default uniforms and textures set. {@code sections}
     * holds each region's chunk section uniform by its key ({@link Slice#region}); {@code quads} are the game's shared
     * quad indices, long enough for every slice drawn ({@link Slice#quadIndices}). {@code ordered} keeps the meshes in
     * exactly the order given, as translucent ones need; otherwise they are drawn buffer by buffer.
     */
    void begin(RenderPass pass, Long2ObjectMap<GpuBufferSlice> sections, GpuBuffer quads, IndexType quadType, boolean ordered) {
        this.pass = pass;
        this.sections = sections;
        this.quads = quads;
        this.quadType = quadType;
        this.ordered = ordered;
        placed = null;
    }

    /** Draws a mesh, now or with others from its buffer by {@link #end} at the latest. */
    void draw(Slice slice) {
        Arena arena = slice.arena;
        if (!arena.open() || slice.freed) return;
        if (arena.queued == 0) {
            if (ordered) flush();
            waiting.add(arena);
        }
        int at = arena.queued * 3;
        if (at == arena.queue.length) arena.queue = java.util.Arrays.copyOf(arena.queue, at * 2);
        arena.queue[at] = slice.indexCount;
        arena.queue[at + 1] = slice.firstVertex;
        arena.queue[at + 2] = slice.firstIndex;
        arena.queued++;
    }

    /** Draws a mesh at once on condition of an occlusion query (see {@link CubeOcclusion}). */
    void drawOnCondition(Slice slice, int query) {
        Arena arena = slice.arena;
        if (!arena.open() || slice.freed) return;
        if (ordered) flush();
        bind(arena);
        CubeOcclusion.beginConditional(query);
        pass.drawIndexed(slice.firstVertex, slice.firstIndex, slice.indexCount, 1, 0);
        CubeOcclusion.endConditional();
        calls++;
    }

    private void bind(Arena arena) {
        Region region = arena.region;
        if (region != placed) {
            pass.setUniform("ChunkSection", sections.get(region.key));
            placed = region;
        }
        pass.setVertexBuffer(0, arena.vertices.slice());
        // Sorted indices are ints ({@link #upload}); a mesh's first index is 0 in the shared quad indices.
        if (arena.sorted) pass.setIndexBuffer(arena.indices, IndexType.INT);
        else pass.setIndexBuffer(quads, quadType);
    }

    private void flush() {
        boolean multi = RenderSystem.getDevice().getDeviceInfo().features().multiDrawDirectSeparate();
        for (Arena arena : waiting) {
            int count = arena.queued;
            arena.queued = 0;
            if (count == 0 || !arena.open()) continue;
            bind(arena);
            int[] queue = arena.queue;
            calls++;
            if (count == 1 || !multi) {
                for (int i = 0; i < count; i++) pass.drawIndexed(queue[i * 3 + 1], queue[i * 3 + 2], queue[i * 3], 1, 0);
                continue;
            }
            int indexBytes = arena.sorted ? Integer.BYTES : quadType.bytes;
            if (counts == null || counts.capacity() < count) {
                int size = Math.max(256, Integer.highestOneBit(count - 1) << 1);
                MemoryUtil.memFree(counts);
                MemoryUtil.memFree(bases);
                MemoryUtil.memFree(offsets);
                counts = MemoryUtil.memAllocInt(size);
                bases = MemoryUtil.memAllocInt(size);
                offsets = MemoryUtil.memAllocPointer(size);
            }
            for (int i = 0; i < count; i++) {
                counts.put(i, queue[i * 3]);
                bases.put(i, queue[i * 3 + 1]);
                offsets.put(i, (long) queue[i * 3 + 2] * indexBytes);
            }
            counts.limit(count);
            bases.limit(count);
            offsets.limit(count);
            pass.multiDrawIndexed(offsets, counts, bases, count);
            counts.limit(counts.capacity());
            bases.limit(bases.capacity());
            offsets.limit(offsets.capacity());
        }
        waiting.clear();
    }

    /** Draws what waits; the caller closes the render pass. */
    void end() {
        flush();
        pass = null;
        sections = null;
        quads = null;
        placed = null;
    }

    /** Draw calls made since last asked. */
    int takeCalls() {
        int made = calls;
        calls = 0;
        return made;
    }

    void close() {
        RenderSystem.assertOnRenderThread();
        for (Region region : regions.values()) {
            for (List<Arena> arenas : region.layers) if (arenas != null) for (Arena arena : arenas) arena.close();
        }
        regions.clear();
        idle.clear();
        waiting.clear();
        arenaCount = 0;
        bytes = usedBytes = 0L;
        MemoryUtil.memFree(counts);
        MemoryUtil.memFree(bases);
        MemoryUtil.memFree(offsets);
        counts = bases = null;
        offsets = null;
    }
}
