package io.earthplatform.minecraft.cubic.play.client;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The layered clouds: three banks at fixed heights (see {@link CloudField}) with thickness and
 * their own winds, ray marched in one full-screen pass and stopped at the scene's depth (see
 * {@link CloudDepth}), so mountains rise through them. They curve with far terrain (see
 * {@code earthplatform.far.curvature}). Weather fronts (see {@link CloudWeather})
 * thin and thicken them over time. Without a shader pack only. Render thread only.
 *
 * <p>Since 26.x the march draws with its own GL program and textures, in raw OpenGL on the game's main
 * target, as far terrain does: it reads the scene's depth as a GL texture, which Blaze3D's pipelines can't
 * bind, and samples a 3D noise texture, which they have no binding for. It leaves the state it found.
 * Another backend than OpenGL, or any failure, turns it off and the plain deck draws instead.
 */
final class CloudVolume {
    private static final int TEXTURE_SIZE = 256;
    /** Texture units: the three coverage textures, the two depths, then the 3D noise. */
    private static final int WEATHER_UNIT = 0, SCENE_UNIT = 3, FAR_UNIT = 4, NOISE_UNIT = 5, UNITS = 6;
    private static final Identifier VERTEX = Identifier.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "shaders/core/earthplatform_weather.vsh");
    private static final Identifier FRAGMENT = Identifier.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "shaders/core/earthplatform_weather.fsh");
    private static final int[] weather = new int[3];
    private static final Map<String, Integer> locations = new HashMap<>();
    private static int program, noiseTexture, array, vertices, repeating, clamping, framebuffer, framebufferColour, framebufferDepth;
    private static boolean failed;

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

    /** Draws the clouds for this frame; false when it can't, and the plain deck draws instead. */
    static boolean draw(ClientLevel level, Matrix4f view, Matrix4f projection, float partialTick, double camX, double camY, double camZ, int height) {
        if (failed) return false;
        try {
            return march(level, view, projection, partialTick, camX, camY, camZ, height);
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
            EarthPlatformMod.LOGGER.warn("The layered clouds could not draw; using the plain cloud deck", failure);
            return false;
        }
    }

    private static boolean march(ClientLevel level, Matrix4f view, Matrix4f projection, float partialTick, double camX, double camY, double camZ, int height) {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        int colour = glId(target.getColorTexture()), depthTexture = target.useDepth ? glId(target.getDepthTexture()) : 0;
        // Not the OpenGL backend: the march has nothing to draw with.
        if (colour == 0) return false;
        if (!ensure()) return false;

        int[] viewport = new int[4];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING), previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), previousArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        int active = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int[] previousTexture = new int[UNITS], previousSampler = new int[UNITS];
        for (int unit = 0; unit < UNITS; unit++) {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
            previousTexture[unit] = GL11C.glGetInteger(unit == NOISE_UNIT ? GL12C.GL_TEXTURE_BINDING_3D : GL11C.GL_TEXTURE_BINDING_2D);
            previousSampler[unit] = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
        }
        boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE), blend = GL11C.glIsEnabled(GL11C.GL_BLEND), depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST), depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        int[] blendFunc = {GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB), GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB),
                GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA), GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA)};
        boolean[] colourMask = new boolean[4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bytes = stack.malloc(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, bytes);
            for (int i = 0; i < 4; i++) colourMask[i] = bytes.get(i) != 0;
        }
        try {
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
            if (colour != framebufferColour || depthTexture != framebufferDepth) {
                framebufferColour = colour;
                framebufferDepth = depthTexture;
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depthTexture, 0);
                GL30C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
            }
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) return false;
            GlStateManager._viewport(0, 0, target.width, target.height);
            // The scene's depth is copied from the framebuffer just bound.
            int depth = CloudDepth.SCENE.texture();
            if (depth == 0) return false;
            int farDepth = CloudDepth.FAR.texture();
            var far = CloudDepth.far();

            GlStateManager._glUseProgram(program);
            Vec3 camera = new Vec3(camX, camY, camZ);
            int argb = level.environmentAttributes().getValue(EnvironmentAttributes.CLOUD_COLOR, camera);
            double ticks = level.getGameTime() + partialTick;
            double angle = Math.toRadians(level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, camera));
            configure(view, projection, farDepth != 0 ? CloudDepth.FAR.projection(projection) : projection, camX, camY, camZ, ticks, height,
                    (argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f,
                    (float) -Math.sin(angle), (float) Math.cos(angle), .2f,
                    steps(System.getProperty("earthplatform.clouds.quality", "high")),
                    opacity(System.getProperty("earthplatform.clouds.opacity", "0.72")), level.getRainLevel(partialTick),
                    far == null ? 0 : far.curvature(), farDepth != 0);

            for (int kind = 0; kind < 3; kind++) bind(WEATHER_UNIT + kind, GL11C.GL_TEXTURE_2D, weather[kind], repeating);
            bind(SCENE_UNIT, GL11C.GL_TEXTURE_2D, depth, clamping);
            // Past the game's blocks, far terrain's own finer depth, when there is one.
            bind(FAR_UNIT, GL11C.GL_TEXTURE_2D, farDepth != 0 ? farDepth : depth, clamping);
            bind(NOISE_UNIT, GL12C.GL_TEXTURE_3D, noiseTexture, repeating);

            GlStateManager._disableCull();
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GlStateManager._enableBlend(0);
            GlStateManager._blendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
            // The march stops at the scene's depth itself, so the depth test is off.
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._colorMask(15);
            GL30C.glBindVertexArray(array);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLE_STRIP, 0, 4);
            return true;
        } finally {
            for (int unit = 0; unit < UNITS; unit++) {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
                GL11C.glBindTexture(unit == NOISE_UNIT ? GL12C.GL_TEXTURE_3D : GL11C.GL_TEXTURE_2D, previousTexture[unit]);
                GL33C.glBindSampler(unit, previousSampler[unit]);
            }
            GlStateManager._activeTexture(active);
            GL30C.glBindVertexArray(previousArray);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
            GlStateManager._glUseProgram(previousProgram);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GlStateManager._colorMask((colourMask[0] ? 1 : 0) | (colourMask[1] ? 2 : 0) | (colourMask[2] ? 4 : 0) | (colourMask[3] ? 8 : 0));
            GlStateManager._depthMask(depthMask);
            GlStateManager._blendFuncSeparate(blendFunc[0], blendFunc[1], blendFunc[2], blendFunc[3]);
            if (cull) GlStateManager._enableCull(); else GlStateManager._disableCull();
            if (blend) GlStateManager._enableBlend(0); else GlStateManager._disableBlend(0);
            if (depthTest) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
            if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
        }
    }

    /** The GL name of a texture, or 0 for none or another backend's. */
    private static int glId(GpuTexture texture) {
        return texture instanceof GlTexture gl && !gl.isClosed() ? gl.glId() : 0;
    }

    /** Binds a texture and a sampler object to a unit. */
    private static void bind(int unit, int type, int texture, int sampler) {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(type, texture);
        GL33C.glBindSampler(unit, sampler);
    }

    /** Makes the program, the quad and the textures on first use; false if that failed, and it stays off. */
    private static boolean ensure() {
        if (program != 0) return true;
        try {
            var resources = Minecraft.getInstance().getResourceManager();
            String vertexSource, fragmentSource;
            try (var in = resources.open(VERTEX)) { vertexSource = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            try (var in = resources.open(FRAGMENT)) { fragmentSource = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            int linked = link(vertexSource, fragmentSource);

            repeating = GL33C.glGenSamplers();
            clamping = GL33C.glGenSamplers();
            for (int sampler : new int[] {repeating, clamping}) {
                int filter = sampler == repeating ? GL11C.GL_LINEAR : GL11C.GL_NEAREST, wrap = sampler == repeating ? GL11C.GL_REPEAT : GL12C.GL_CLAMP_TO_EDGE;
                GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MIN_FILTER, filter);
                GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MAG_FILTER, filter);
                GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_WRAP_S, wrap);
                GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_WRAP_T, wrap);
                GL33C.glSamplerParameteri(sampler, GL12C.GL_TEXTURE_WRAP_R, wrap);
            }

            int previousArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING), previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
            array = GL30C.glGenVertexArrays();
            vertices = GL15C.glGenBuffers();
            GL30C.glBindVertexArray(array);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertices);
            // Position, UV and colour of a screen-filling strip.
            float[] quad = {-1, -1, 0, 0, 0, 1, 1, 1, 1,   1, -1, 0, 1, 0, 1, 1, 1, 1,   -1, 1, 0, 0, 1, 1, 1, 1, 1,   1, 1, 0, 1, 1, 1, 1, 1, 1};
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer data = stack.mallocFloat(quad.length);
                data.put(quad).flip();
                GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, data, GL15C.GL_STATIC_DRAW);
            }
            int stride = 9 * Float.BYTES;
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, stride, 0);
            GL20C.glEnableVertexAttribArray(1);
            GL20C.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, stride, 3L * Float.BYTES);
            GL20C.glEnableVertexAttribArray(2);
            GL20C.glVertexAttribPointer(2, 4, GL11C.GL_FLOAT, false, stride, 5L * Float.BYTES);
            GL30C.glBindVertexArray(previousArray);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);

            framebuffer = GL30C.glGenFramebuffers();
            for (int kind = 0; kind < 3; kind++) weather[kind] = createWeatherTexture(kind);
            noiseTexture = createNoiseTexture();
            program = linked;
            return true;
        } catch (java.io.IOException | RuntimeException failure) {
            failed = true;
            EarthPlatformMod.LOGGER.warn("The layered cloud shader could not load; using the plain cloud deck", failure);
            return false;
        }
    }

    private static int link(String vertexSource, String fragmentSource) {
        int vertex = compile(GL20C.GL_VERTEX_SHADER, vertexSource), fragment;
        try { fragment = compile(GL20C.GL_FRAGMENT_SHADER, fragmentSource); }
        catch (IllegalStateException failure) { GL20C.glDeleteShader(vertex); throw failure; }
        int linked = GL20C.glCreateProgram();
        GL20C.glAttachShader(linked, vertex);
        GL20C.glAttachShader(linked, fragment);
        GL20C.glBindAttribLocation(linked, 0, "Position");
        GL20C.glBindAttribLocation(linked, 1, "UV0");
        GL20C.glBindAttribLocation(linked, 2, "Color");
        GL20C.glLinkProgram(linked);
        GL20C.glDetachShader(linked, vertex);
        GL20C.glDetachShader(linked, fragment);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        if (GL20C.glGetProgrami(linked, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(linked);
            GL20C.glDeleteProgram(linked);
            throw new IllegalStateException("Could not link the cloud shader: " + log);
        }
        return linked;
    }

    private static int compile(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile the cloud " + (type == GL20C.GL_VERTEX_SHADER ? "vertex" : "fragment") + " shader: " + log);
        }
        return shader;
    }

    /** Bank {@code kind}'s coverage texture, made from {@link CloudField#density}; the shader reads its alpha. */
    private static int createWeatherTexture(int kind) {
        ByteBuffer bytes = MemoryUtil.memAlloc(TEXTURE_SIZE * TEXTURE_SIZE * 4);
        int texture = GL11C.glGenTextures();
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            for (int y = 0; y < TEXTURE_SIZE; y++)
                for (int x = 0; x < TEXTURE_SIZE; x++) {
                    int alpha = (int) Math.round(CloudField.density(kind, (x + .5) / TEXTURE_SIZE, (y + .5) / TEXTURE_SIZE) * 255);
                    bytes.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) alpha);
                }
            bytes.flip();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, TEXTURE_SIZE, TEXTURE_SIZE, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, bytes);
            // The shader samples level 0 only, so no mipmaps.
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
            MemoryUtil.memFree(bytes);
        }
        return texture;
    }

    /** A repeating 64-cubed noise lattice, about 256 KiB, filtered by the GPU. */
    private static int createNoiseTexture() {
        int texture = GL11C.glGenTextures();
        int previous = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        ByteBuffer bytes = MemoryUtil.memAlloc(64 * 64 * 64);
        try {
            CloudWeather.writeNoise(bytes);
            bytes.flip();
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL30C.GL_R8, 64, 64, 64, 0, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, bytes);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        } finally {
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, previous);
            MemoryUtil.memFree(bytes);
        }
        return texture;
    }

    private static int location(String name) {
        return locations.computeIfAbsent(name, key -> GL20C.glGetUniformLocation(program, key));
    }

    private static void set(String name, float... values) {
        int at = location(name);
        if (at < 0) return;
        switch (values.length) {
            case 1 -> GL20C.glUniform1f(at, values[0]);
            case 2 -> GL20C.glUniform2f(at, values[0], values[1]);
            case 3 -> GL20C.glUniform3f(at, values[0], values[1], values[2]);
            default -> GL20C.glUniform4f(at, values[0], values[1], values[2], values[3]);
        }
    }

    private static void set(String name, int value) {
        int at = location(name);
        if (at >= 0) GL20C.glUniform1i(at, value);
    }

    private static void set(String name, Matrix4f value) {
        int at = location(name);
        if (at < 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20C.glUniformMatrix4fv(at, false, value.get(stack.mallocFloat(16)));
        }
    }

    /** Sets every uniform the march reads; the program is bound. */
    private static void configure(Matrix4f view, Matrix4f projection, Matrix4f farProjection, double x, double y, double z, double ticks, double height,
                                  float r, float g, float b, float sunX, float sunY, float sunZ, int steps, float opacity, float rain,
                                  float curvature, boolean useFarDepth) {
        set("Sampler0", WEATHER_UNIT);
        set("Weather1", WEATHER_UNIT + 1);
        set("Weather2", WEATHER_UNIT + 2);
        set("SceneDepth", SCENE_UNIT);
        set("FarDepth", FAR_UNIT);
        set("NoiseVolume", NOISE_UNIT);
        set("ColorModulator", 1f, 1f, 1f, 1f);
        set("UseFarDepth", useFarDepth ? 1f : 0f);
        set("FarInvProjection", new Matrix4f(farProjection).invert());
        set("SkyInvProjection", new Matrix4f(projection).invert());
        set("SkyInvView", new Matrix4f(view).invert());
        set("CameraHeight", (float) y);
        set("CloudLight", r, g, b);
        set("SunDirection", sunX, sunY, sunZ);
        set("MarchSteps", Math.clamp(steps, 8, 32));
        set("MaxOpacity", opacity);
        set("Curvature", curvature);
        // The phases are fractions of each texture's period, so the patterns stay fixed to the world as the camera moves.
        set("WeatherPhase", (float) CloudWeather.frac(x / CloudWeather.REGION_PERIOD), (float) CloudWeather.frac(z / CloudWeather.REGION_PERIOD),
                (float) CloudWeather.frac(ticks / CloudWeather.TIME_PERIOD), Math.clamp(rain, 0, 1) * .2f);
        var bands = CloudField.bands(height);
        for (int i = 0; i < bands.length; i++) {
            var band = bands[i];
            set("Band" + i, (float) band.height(), (float) band.thickness(), (float) band.period(), (float) band.opacity());
            double c = Math.cos(band.turn()), s = Math.sin(band.turn());
            set("Phase" + i, (float) CloudWeather.frac((c * x - s * z + ticks * band.wind()) / band.period()),
                    (float) CloudWeather.frac((s * x + c * z + ticks * band.wind() * .23) / band.period()));
        }
        set("NoisePhase0", (float) CloudWeather.frac(x / 18000), (float) CloudWeather.frac(y / 18000), (float) CloudWeather.frac(z / 18000));
        set("NoisePhase1", (float) CloudWeather.frac(x / 4500), (float) CloudWeather.frac(y / 4500), (float) CloudWeather.frac(z / 4500));
    }
}
