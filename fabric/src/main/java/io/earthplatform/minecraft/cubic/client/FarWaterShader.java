package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;

/**
 * Far water's shader, without a shader pack: the game's translucent shader, with each vertex
 * dropped by the same curve as far terrain's (see {@link EarthView}) and drawn a little nearer in
 * depth rather than shrunk toward the camera, which moved each column's edges apart. With no curve
 * it draws as the game's translucent shader did.
 *
 * <p>Until 26.x this was a ShaderInstance beside the game's own translucent one, which drew far
 * water on flat ground. The game's shaders are now Blaze3D pipelines fed by uniform buffers, so this
 * is a GL program of its own, from the same sources, drawn with the translucent pipeline's state.
 * Render thread only.
 */
final class FarWaterShader {
    /** Texture units the atlas and lightmap are bound to while far water draws, as the game's Sampler0 and Sampler2. */
    private static final int ATLAS_UNIT = 0, LIGHT_UNIT = 2;

    @Nullable private static FarWaterShader shader;
    private static boolean failed;

    private final int program;
    private final int modelView, projection, chunkOffset, colourModulator, fogStart, fogEnd, fogColour, fogShape,
            curvature, flat, depthBias, sampler0, sampler2;
    private int previousProgram, previousActive, previousAtlas, previousLight, previousDepthFunc;
    private boolean previousBlend, previousCull, previousDepthTest, previousDepthMask;

    private FarWaterShader(int program) {
        this.program = program;
        modelView = GL20C.glGetUniformLocation(program, "ModelViewMat");
        projection = GL20C.glGetUniformLocation(program, "ProjMat");
        chunkOffset = GL20C.glGetUniformLocation(program, "ChunkOffset");
        colourModulator = GL20C.glGetUniformLocation(program, "ColorModulator");
        fogStart = GL20C.glGetUniformLocation(program, "FogStart");
        fogEnd = GL20C.glGetUniformLocation(program, "FogEnd");
        fogColour = GL20C.glGetUniformLocation(program, "FogColor");
        fogShape = GL20C.glGetUniformLocation(program, "FogShape");
        curvature = GL20C.glGetUniformLocation(program, "EarthCurvature");
        flat = GL20C.glGetUniformLocation(program, "EarthFlat");
        depthBias = GL20C.glGetUniformLocation(program, "EarthDepthBias");
        sampler0 = GL20C.glGetUniformLocation(program, "Sampler0");
        sampler2 = GL20C.glGetUniformLocation(program, "Sampler2");
    }

    /** The shader, loaded on first use; null if it failed to load. */
    @Nullable static FarWaterShader get() {
        if (shader == null && !failed) {
            try { shader = load(Minecraft.getInstance().getResourceManager()); }
            catch (IOException | RuntimeException failure) {
                failed = true;
                EarthPlatformMod.LOGGER.warn("The far water shader could not load; far water is not drawn and the horizon stays flat", failure);
            }
        }
        return shader;
    }

    /** Loads the shader from the mod's namespace, its inputs placed where far water's vertex arrays point (see {@link FarWaterBuffers}). */
    static FarWaterShader load(ResourceProvider resources) throws IOException {
        String vertex = source(resources, "shaders/core/earthplatform_water.vsh");
        String fragment = source(resources, "shaders/core/earthplatform_water.fsh");
        int vertexShader = compile(GL20C.GL_VERTEX_SHADER, vertex);
        int fragmentShader;
        try { fragmentShader = compile(GL20C.GL_FRAGMENT_SHADER, fragment); }
        catch (IllegalStateException failure) { GL20C.glDeleteShader(vertexShader); throw failure; }
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertexShader);
        GL20C.glAttachShader(program, fragmentShader);
        List<VertexFormatElement> elements = DefaultVertexFormat.BLOCK.getElements();
        for (int i = 0; i < elements.size(); i++) GL20C.glBindAttribLocation(program, i, elements.get(i).name());
        GL20C.glLinkProgram(program);
        GL20C.glDetachShader(program, vertexShader);
        GL20C.glDetachShader(program, fragmentShader);
        GL20C.glDeleteShader(vertexShader);
        GL20C.glDeleteShader(fragmentShader);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(program);
            GL20C.glDeleteProgram(program);
            throw new IllegalStateException("Could not link the far water shader: " + log);
        }
        return new FarWaterShader(program);
    }

    private static String source(ResourceProvider resources, String path) throws IOException {
        try (InputStream in = resources.getResourceOrThrow(Identifier.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, path)).open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int compile(int type, String source) {
        int compiled = GL20C.glCreateShader(type);
        GL20C.glShaderSource(compiled, source);
        GL20C.glCompileShader(compiled);
        if (GL20C.glGetShaderi(compiled, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(compiled);
            GL20C.glDeleteShader(compiled);
            throw new IllegalStateException("Could not compile the far water shader: " + log);
        }
        return compiled;
    }

    /**
     * Puts the shader in use, with the game's translucent state (blending, depth tested and
     * written, back faces culled), its atlas and lightmap, the game's fog (see {@link FarFog}),
     * and this draw's curve: {@code bias} steps of the depth range nearer. {@link #end} puts back
     * what this changed.
     */
    void begin(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, float curve, float flatRadius, float bias) {
        previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        previousActive = FarGl.activeTexture();
        previousBlend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        previousCull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        previousDepthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        previousDepthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        previousDepthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        GlStateManager._glUseProgram(program);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20C.glUniformMatrix4fv(modelView, false, modelViewMatrix.get(stack.mallocFloat(16)));
            GL20C.glUniformMatrix4fv(projection, false, projectionMatrix.get(stack.mallocFloat(16)));
        }
        GL20C.glUniform3f(chunkOffset, 0.0F, 0.0F, 0.0F);
        GL20C.glUniform4f(colourModulator, 1.0F, 1.0F, 1.0F, 1.0F);
        GL20C.glUniform1f(fogStart, FarFog.start());
        GL20C.glUniform1f(fogEnd, FarFog.end());
        float[] fog = FarFog.colour();
        GL20C.glUniform4f(fogColour, fog[0], fog[1], fog[2], fog[3]);
        GL20C.glUniform1i(fogShape, FarFog.shape());
        GL20C.glUniform1f(curvature, curve);
        GL20C.glUniform1f(flat, flatRadius);
        GL20C.glUniform1f(depthBias, bias);
        GL20C.glUniform1i(sampler0, ATLAS_UNIT);
        GL20C.glUniform1i(sampler2, LIGHT_UNIT);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + ATLAS_UNIT);
        previousAtlas = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(FarGl.blockAtlasId());
        FarGl.bindSampler(ATLAS_UNIT, FarGl.blockAtlasSampler());
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + LIGHT_UNIT);
        previousLight = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(FarGl.lightmapId());
        FarGl.bindSampler(LIGHT_UNIT, FarGl.lightmapSampler());
        GlStateManager._activeTexture(previousActive);
        GlStateManager._enableBlend(0);
        GlStateManager._blendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager._blendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_LEQUAL);
        GlStateManager._depthMask(true);
        GlStateManager._enableCull();
    }

    /** Where the vertices drawn next are placed from the camera (the game's ChunkOffset). */
    void offset(float x, float y, float z) { GL20C.glUniform3f(chunkOffset, x, y, z); }

    /** The model-view matrix the vertices drawn next go through. */
    void modelView(Matrix4f matrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20C.glUniformMatrix4fv(modelView, false, matrix.get(stack.mallocFloat(16)));
        }
    }

    /** Puts back what {@link #begin} changed. */
    void end() {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + ATLAS_UNIT);
        GlStateManager._bindTexture(previousAtlas);
        FarGl.bindSampler(ATLAS_UNIT, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + LIGHT_UNIT);
        GlStateManager._bindTexture(previousLight);
        FarGl.bindSampler(LIGHT_UNIT, 0);
        GlStateManager._activeTexture(previousActive);
        GlStateManager._glUseProgram(previousProgram);
        if (previousBlend) GlStateManager._enableBlend(0); else GlStateManager._disableBlend(0);
        if (previousCull) GlStateManager._enableCull(); else GlStateManager._disableCull();
        if (previousDepthTest) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
        GlStateManager._depthFunc(previousDepthFunc);
        GlStateManager._depthMask(previousDepthMask);
    }
}
