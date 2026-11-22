package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeLight;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.Timings;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * Off-thread cube compile: lights the 48-block neighbourhood with {@link CubeLight} and
 * tessellates the centre cube with the vanilla block and fluid renderers.
 */
final class CubeMesher {
    private static final int SIZE = CubeLight.SIZE;
    /** The layers a cube's mesh has, in the order drawn; a layer's index is its place here. */
    static final List<ChunkSectionLayer> LAYERS = List.of(ChunkSectionLayer.values());
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Copied on the client thread; the worker only reads it. */
    record Snapshot(long key, int version, int cx, int cy, int cz,
                    @Nullable PalettedContainer<BlockState>[] states, @Nullable PalettedContainerRO<Holder<Biome>>[] biomes,
                    @Nullable int[][] tops, float camX, float camY, float camZ, CardinalLighting lighting,
                    @Nullable Object2IntMap<BlockState> shaderIds, Map<BlockPos, BlockEntity> blockEntities) {}

    record Layer(int index, MeshData mesh, ByteBufferBuilder memory) {}
    /** {@code visibility}: which faces the cube's air joins ({@link CubeCaves#of}). */
    record Built(long key, int version, List<Layer> layers, byte[] light, long visibility) {
        void free() { for (Layer layer : layers) { layer.mesh.close(); layer.memory.close(); } }
    }

    /**
     * A block whose six faces vanilla's own face check ({@link Block#shouldRenderFace}) hides, and whose model is one of
     * vanilla's plain kinds with no faces that are never hidden, draws nothing: it is left out before the model pass, which
     * asked its model for every face's quads first. Below the ground that is nearly every block. {@code false} sends every
     * block through the model pass, for comparing. The same vertices.
     */
    private static final boolean SKIP_BURIED = !"false".equals(System.getProperty("earthplatform.cubes.skipBuried"));
    /**
     * {@code -Dearthplatform.cubes.skipInterior=true}: a cube of solid stone or deepslate sealed in by more of it on all
     * six faces, under its column's sky ({@link CubeInterior}), is neither lit nor meshed. Every block in it is buried,
     * so the mesh would be empty, the light dark and no face joined to another; this skips the light, the walk over its
     * blocks and the cave graph that find that out.
     */
    private static final boolean SKIP_INTERIOR = Boolean.getBoolean("earthplatform.cubes.skipInterior");
    // Since 26.2 the model renderer places each block by its offset in the cube and draws its random numbers from its
    // own single-threaded source, so the old flags cubes.reusePose and cubes.plainRandom have nothing left to change.
    /**
     * With {@code -Dearthplatform.cubes.checkMesh=true}, every cube is also meshed and lit the plain way, every speedup
     * off, and a warning names any cube whose light or vertices differ.
     */
    private static final boolean CHECK_MESH = Boolean.getBoolean("earthplatform.cubes.checkMesh");

    /** Which speedups a mesh uses: the flags', or none, for {@link #CHECK_MESH}. */
    private record Mode(boolean skipBuried, boolean fastLight, boolean skipInterior) {}
    private static final Mode FLAGS = new Mode(SKIP_BURIED, CubeLight.FAST_LIGHT, SKIP_INTERIOR);
    private static final Mode PLAIN = new Mode(false, false, false);
    private static final java.util.concurrent.atomic.AtomicInteger CHECKED = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger DIFFERENT = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger NORMALS = new java.util.concurrent.atomic.AtomicInteger();
    /** Cubes {@link #SKIP_INTERIOR} left unmeshed, for the performance log. */
    private static final java.util.concurrent.atomic.LongAdder INTERIOR_SKIPPED = new java.util.concurrent.atomic.LongAdder();

    static long interiorSkipped() { return INTERIOR_SKIPPED.sum(); }

    private static final ThreadLocal<Work> WORK = ThreadLocal.withInitial(Work::new);

    private static final class Work {
        /** The same kind of source as the model renderer's own, to ask a model for its parts as it does. */
        final RandomSource random = RandomSource.createThreadLocalInstance(0L);
        final List<BlockStateModelPart> parts = new java.util.ArrayList<>();
        final BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
    }

    static Built build(Snapshot s) {
        Work work = WORK.get();
        Built built = build(s, work, FLAGS);
        if (CHECK_MESH) check(s, work, built, build(s, work, PLAIN));
        // Last, once the translucent quads are sorted and the check has compared the meshes as built.
        if (CubeRegions.ON) for (Layer layer : built.layers) CubeRegions.shift(layer.mesh, s.cx, s.cy, s.cz);
        return built;
    }

    private static Built build(Snapshot s, Work work, Mode mode) {
        if (mode.skipInterior && s.blockEntities.isEmpty() && CubeInterior.enclosedStone(s.states, s.tops, s.cy) && onlyCulledFaces(s, work)) {
            INTERIOR_SKIPPED.increment();
            // All blocks opaque: vanilla's graph joins no face to another.
            return new Built(s.key, s.version, List.of(), new byte[4096], 0L);
        }
        long lightStart = System.nanoTime();
        CubeLight.Field field = CubeLight.light(s.states, s.tops, s.cy, mode.fastLight);
        Timings.CUBE_LIGHT.since(lightStart);
        View view = new View(s, field);
        List<Layer> layers = mesh(s, work, field, view, mode);
        return new Built(s.key, s.version, layers, CubeLight.centre(field), visibility(field, view));
    }

    /**
     * Whether every block model in the cube is vanilla's simplest kind with no quads that no face hides. A resource pack
     * may give stone such quads, and a mod's or a weighted model may draw what its faces do not say, so those are meshed.
     */
    private static boolean onlyCulledFaces(Snapshot s, Work work) {
        BlockStateModelSet blocks = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        return !s.states[13].maybeHas(state -> {
            BlockStateModel model = blocks.get(state);
            return model.getClass() != SingleVariant.class || unculled(model, work, 0L);
        });
    }

    /** Which of the cube's faces its air joins, as vanilla works it out for a section: full opaque blocks close it. */
    private static long visibility(CubeLight.Field field, View view) {
        net.minecraft.client.renderer.chunk.VisGraph graph = new net.minecraft.client.renderer.chunk.VisGraph();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x0 = view.baseX + 16, y0 = view.baseY + 16, z0 = view.baseZ + 16;
        boolean any = false;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState state = field.states[CubeLight.index(x + 16, y + 16, z + 16)];
            if (state.isAir()) continue;
            pos.set(x0 + x, y0 + y, z0 + z);
            if (state.isSolidRender()) { graph.setOpaque(pos); any = true; }
        }
        return any ? CubeCaves.of(graph.resolve()) : CubeCaves.OPEN;
    }

    /**
     * For {@link #CHECK_MESH}: compares a cube's mesh with the one made the plain way, and frees that one. Where they
     * differ, it meshes the cube again with each speedup alone to name the ones that change it.
     */
    private static void check(Snapshot s, Work work, Built built, Built plain) {
        try {
            String differ = differ(built, plain, false);
            int checked = CHECKED.incrementAndGet();
            if (differ == null && differ(built, plain, true) != null) NORMALS.incrementAndGet();
            if (differ != null) {
                StringBuilder culprits = new StringBuilder();
                Mode[] alone = {new Mode(true, false, false), new Mode(false, true, false), new Mode(false, false, true)};
                String[] names = {"skipBuried", "fastLight", "skipInterior"};
                for (int i = 0; i < alone.length; i++) {
                    Built one = build(s, work, alone[i]);
                    if (differ(one, plain, false) != null) culprits.append(' ').append(names[i]);
                    one.free();
                }
                EarthPlatformMod.LOGGER.warn("Cube mesh check: cube {} differs from the plain mesh in its {}, with{} alone ({} of {} differ)",
                        CubeKey.toString(built.key), differ, culprits, DIFFERENT.incrementAndGet(), checked);
            }
            if (checked % 1000 == 0) EarthPlatformMod.LOGGER.info("Cube mesh check: {} meshes compared, {} differ, {} more in a shader pack's normals alone",
                    checked, DIFFERENT.get(), NORMALS.get());
        } finally {
            plain.free();
        }
    }

    /**
     * Where a mesh differs from the plain one, or null. Under a shader pack, Iris works a quad's normal out again only while
     * the render thread is drawing the level (a flag it shares between threads), so the same mesh made twice can differ in
     * normals alone; {@code normals} false leaves those out of the vertices compared.
     */
    @Nullable static String differ(Built built, Built plain, boolean normals) {
        if (!java.util.Arrays.equals(built.light, plain.light)) return "light";
        // Which faces the air joins: skipInterior says none, and a wrong answer hides caves behind the cube.
        if (built.visibility != plain.visibility) return "visibility " + Long.toHexString(built.visibility) + " against " + Long.toHexString(plain.visibility);
        if (built.layers.size() != plain.layers.size()) return "layers " + built.layers.size() + " against " + plain.layers.size();
        for (int i = 0; i < built.layers.size(); i++) {
            Layer a = built.layers.get(i), b = plain.layers.get(i);
            if (a.index != b.index) return "layer " + a.index + " against " + b.index;
            if (!a.mesh.drawState().equals(b.mesh.drawState())) return "layer " + a.index + " draw state " + a.mesh.drawState() + " against " + b.mesh.drawState();
            // Element by element: a format's padding is never written, so it holds whatever the memory held.
            java.nio.ByteBuffer va = a.mesh.vertexBuffer(), vb = b.mesh.vertexBuffer();
            VertexFormat format = a.mesh.drawState().format();
            int size = format.getVertexSize(), vertices = a.mesh.drawState().vertexCount();
            boolean skipNormals = !normals && format != DefaultVertexFormat.BLOCK;
            for (int vertex = 0; vertex < vertices; vertex++) {
                for (com.mojang.blaze3d.vertex.VertexFormatElement element : format.getElements()) {
                    if (skipNormals && DefaultVertexFormat.NORMAL_SEMANTIC_NAME.equals(element.name())) continue;
                    int from = vertex * size + element.offset();
                    for (int k = from; k < from + element.format().blockSize(); k++) {
                        if (va.get(k) == vb.get(k)) continue;
                        StringBuilder bytes = new StringBuilder();
                        for (int j = vertex * size; j < (vertex + 1) * size; j++) bytes.append(String.format(" %02x/%02x", va.get(j), vb.get(j)));
                        return "layer " + a.index + " vertices, first at byte " + k + " (vertex " + vertex + " of " + vertices + ", " + element + "):" + bytes;
                    }
                }
            }
            if (!java.util.Objects.equals(a.mesh.indexBuffer(), b.mesh.indexBuffer())) return "layer " + a.index + " indices";
        }
        return null;
    }

    private static List<Layer> mesh(Snapshot s, Work work, CubeLight.Field field, View view, Mode mode) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockStateModelSet blocks = minecraft.getModelManager().getBlockStateModelSet();
        // Set up as vanilla's section compiler sets them up for each section it compiles.
        ModelBlockRenderer renderer = new ModelBlockRenderer(minecraft.options.ambientOcclusion().get(), true, minecraft.getBlockColors());
        FluidRenderer fluids = new FluidRenderer(minecraft.getModelManager().getFluidStateModelSet());
        boolean cutoutLeaves = minecraft.options.cutoutLeaves().get();
        Output output = new Output(s.shaderIds);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x0 = s.cx << 4, y0 = s.cy << 4, z0 = s.cz << 4;
        BlockModelLighter.enableCaching();
        try {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = field.states[CubeLight.index(x + 16, y + 16, z + 16)];
                if (state.isAir()) continue;
                pos.set(x0 + x, y0 + y, z0 + z);
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    output.begin(fluid.createLegacyBlock(), true, x, y, z, false);
                    fluids.tesselate(view, pos, output, state, fluid);
                    output.end();
                }
                if (state.getRenderShape() != RenderShape.MODEL) continue;
                BlockStateModel model = blocks.get(state);
                long seed = state.getSeed(pos);
                // Every face is hidden, so it draws only the quads no face hides, asked for as the model pass does.
                if (mode.skipBuried && plain(model) && buried(state, view, pos, work.near) && !unculled(model, work, seed)) continue;
                output.begin(state, false, x, y, z, ModelBlockRenderer.forceOpaque(cutoutLeaves, state));
                renderer.tesselateBlock(output, x, y, z, view, pos, state, model, seed);
                output.end();
            }
        } finally {
            BlockModelLighter.clearCache();
        }
        List<Layer> layers = new java.util.ArrayList<>();
        for (int i = 0; i < output.builders.length; i++) {
            if (output.builders[i] == null) continue;
            MeshData mesh = output.builders[i].build();
            if (mesh == null) { output.memory[i].close(); continue; }
            if (LAYERS.get(i) == ChunkSectionLayer.TRANSLUCENT) {
                mesh.sortQuads(output.memory[i], VertexSorting.byDistance(s.camX - x0, s.camY - y0, s.camZ - z0));
            }
            layers.add(new Layer(i, mesh, output.memory[i]));
        }
        return layers;
    }

    /** Whether the model, its parts picked with the seed as the model renderer picks them, has quads no face hides. */
    private static boolean unculled(BlockStateModel model, Work work, long seed) {
        work.random.setSeed(seed);
        work.parts.clear();
        model.collectParts(work.random, work.parts);
        try {
            for (BlockStateModelPart part : work.parts) if (!part.getQuads(null).isEmpty()) return true;
            return false;
        } finally {
            work.parts.clear();
        }
    }

    /**
     * Where the model and fluid renderers put a block's quads: each layer's builder, started when first needed. The
     * block's shader pack id ({@link ShaderBlocks}) is set on each builder the block writes to, and cleared after it.
     */
    private static final class Output implements BlockQuadOutput, FluidRenderer.Output {
        final BufferBuilder[] builders = new BufferBuilder[LAYERS.size()];
        final ByteBufferBuilder[] memory = new ByteBufferBuilder[LAYERS.size()];
        @Nullable private final Object2IntMap<BlockState> ids;
        private BlockState state = AIR;
        private boolean fluid, opaque;
        private int x, y, z, begun;

        Output(@Nullable Object2IntMap<BlockState> ids) { this.ids = ids; }

        /** {@code opaque}: every quad goes to the solid layer, as vanilla puts leaves there without cutout leaves. */
        void begin(BlockState state, boolean fluid, int x, int y, int z, boolean opaque) {
            this.state = state; this.fluid = fluid; this.x = x; this.y = y; this.z = z; this.opaque = opaque;
            begun = 0;
        }

        void end() {
            for (int i = 0; i < builders.length; i++) if ((begun & 1 << i) != 0) ShaderBlocks.end(builders[i], ids);
            begun = 0;
        }

        private BufferBuilder layer(ChunkSectionLayer layer) {
            int i = layer.ordinal();
            if (builders[i] == null) {
                memory[i] = new ByteBufferBuilder(i == 0 ? 131072 : 16384);
                builders[i] = new BufferBuilder(memory[i], PrimitiveTopology.QUADS, layer.vertexFormat());
            }
            if ((begun & 1 << i) == 0) {
                begun |= 1 << i;
                ShaderBlocks.begin(builders[i], ids, state, fluid, x, y, z);
            }
            return builders[i];
        }

        @Override public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            layer(opaque ? ChunkSectionLayer.SOLID : quad.materialInfo().layer()).putBlockBakedQuad(x, y, z, quad, instance);
        }

        @Override public VertexConsumer getBuilder(ChunkSectionLayer layer) { return layer(layer); }
    }

    /**
     * Models of vanilla's plain kinds: their model pass draws a face's quads only where {@link Block#shouldRenderFace}
     * says, and the quads no face hides. A model of any other kind, such as a mod's connected textures, always goes
     * through the model pass.
     */
    private static boolean plain(BlockStateModel model) {
        Class<?> kind = model.getClass();
        return kind == SingleVariant.class || kind == WeightedVariants.class;
    }

    /** Faces in the order most likely to show, so a block in sight is found out at once. */
    private static final Direction[] FACES = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN};

    /** Whether vanilla's face check hides all six faces of the block, as the model pass asks it. */
    private static boolean buried(BlockState state, View view, BlockPos pos, BlockPos.MutableBlockPos near) {
        for (Direction face : FACES) {
            if (Block.shouldRenderFace(state, view.getBlockState(near.setWithOffset(pos, face)), face)) return false;
        }
        return true;
    }

    /** The lit 48-block neighbourhood as a vanilla block getter. */
    private static final class View implements BlockAndTintGetter {
        private final Snapshot s;
        private final CubeLight.Field field;
        final int baseX, baseY, baseZ;

        View(Snapshot s, CubeLight.Field field) {
            this.s = s; this.field = field;
            baseX = (s.cx - 1) << 4; baseY = (s.cy - 1) << 4; baseZ = (s.cz - 1) << 4;
        }

        private int at(BlockPos pos) {
            int x = pos.getX() - baseX, y = pos.getY() - baseY, z = pos.getZ() - baseZ;
            return x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE ? -1 : CubeLight.index(x, y, z);
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            int i = at(pos);
            if (i < 0) return AIR;
            int rx = pos.getX() - baseX, ry = pos.getY() - baseY, rz = pos.getZ() - baseZ;
            if (ry >> 4 != 1 || s.states[(ry >> 4) * 9 + (rz >> 4) * 3 + (rx >> 4)] != null) return field.states[i];
            // Beside a cube not loaded yet (the edge of the streamed area), water carries on as it
            // does in the far terrain past it, instead of ending in a wall.
            BlockState edge = field.states[CubeLight.index(Mth.clamp(rx, 16, 31), ry, Mth.clamp(rz, 16, 31))];
            return edge.getFluidState().isEmpty() ? AIR : edge;
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override @Nullable public BlockEntity getBlockEntity(BlockPos pos) { return s.blockEntities.get(pos); }
        @Override public int getHeight() { return CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1; }
        @Override public int getMinY() { return CubeWorld.MIN_Y; }

        /** How much each face is shaded, as the client level has it (flat in the Nether). */
        @Override public CardinalLighting cardinalLighting() { return s.lighting; }

        @Override public LevelLightEngine getLightEngine() { return Minecraft.getInstance().level.getLightEngine(); }

        @Override public int getBrightness(LightLayer layer, BlockPos pos) {
            int i = at(pos);
            if (i < 0) return layer == LightLayer.SKY ? 15 : 0;
            return layer == LightLayer.SKY ? field.sky[i] : field.block[i];
        }

        @Override public int getRawBrightness(BlockPos pos, int amount) {
            return Math.max(getBrightness(LightLayer.SKY, pos) - amount, getBrightness(LightLayer.BLOCK, pos));
        }

        @Nullable private Holder<Biome> biome(int x, int y, int z) {
            int rx = x - baseX, ry = y - baseY, rz = z - baseZ;
            if (rx < 0 || ry < 0 || rz < 0 || rx >= SIZE || ry >= SIZE || rz >= SIZE) return null;
            PalettedContainerRO<Holder<Biome>> container = s.biomes[(ry >> 4) * 9 + (rz >> 4) * 3 + (rx >> 4)];
            return container == null ? null : container.get((rx & 15) >> 2, (ry & 15) >> 2, (rz & 15) >> 2);
        }

        /**
         * Blend over 4-block biome cells weighted by each block's distance to the cells' centres, falling to nothing two
         * cells (8 blocks) away. The tint then changes block by block across a border, as a gradient, instead of in
         * 4-block steps: a box blend gives every block of a cell the same samples, so a deep-sea edge became a staircase.
         */
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            // The block's centre in cell units, measured from the centre of cell 0.
            double u = (pos.getX() + 0.5 - 2) / 4.0, v = (pos.getZ() + 0.5 - 2) / 4.0;
            int i0 = Mth.floor(u), j0 = Mth.floor(v);
            double r = 0, g = 0, b = 0, n = 0;
            for (int j = j0 - 1; j <= j0 + 2; j++) {
                double wz = Math.max(0, 1 - Math.abs(v - j) / 2);
                for (int i = i0 - 1; i <= i0 + 2; i++) {
                    double w = wz * Math.max(0, 1 - Math.abs(u - i) / 2);
                    if (w <= 0) continue;
                    int x = i * 4 + 2, z = j * 4 + 2;
                    Holder<Biome> biome = biome(x, pos.getY(), z);
                    if (biome == null) continue;
                    int color = resolver.getColor(biome.value(), x, z);
                    r += (color >> 16 & 255) * w; g += (color >> 8 & 255) * w; b += (color & 255) * w; n += w;
                }
            }
            if (n == 0) return 0xFFFFFF;
            return (int) Math.round(r / n) << 16 | (int) Math.round(g / n) << 8 | (int) Math.round(b / n);
        }
    }
}
