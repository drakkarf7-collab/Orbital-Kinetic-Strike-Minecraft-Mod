package fr.orbitalstrike.client.render.gl;

import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom GLSL shader program (vertex + fragment) loaded from mod resources.
 * <p>Minecraft's {@code ShaderProgram} is not used here because it enforces fixed vertex
 * formats and uniforms. This class accepts arbitrary uniforms (float, vec3, mat4, sampler)
 * and powers the 3D space scene and HDR post-processing.</p>
 * <p>Fixed vertex attributes: 0 = Position, 1 = Normal, 2 = UV0.</p>
 */
public final class GlProgram implements AutoCloseable {
    public static final int ATTR_POSITION = 0, ATTR_NORMAL = 1, ATTR_UV = 2;

    private final int id;
    private final String name;
    private final Map<String, Integer> locations = new HashMap<>();

    private GlProgram(int id, String name) {
        this.id = id;
        this.name = name;
    }

    /** Loads, compiles, and links a program. Throws IllegalStateException with the GLSL log on failure. */
    public static GlProgram load(ResourceManager resources, Identifier vertex, Identifier fragment) throws IOException {
        int vs = compile(GL20.GL_VERTEX_SHADER, read(resources, vertex), vertex.toString());
        int fs = compile(GL20.GL_FRAGMENT_SHADER, read(resources, fragment), fragment.toString());
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glBindAttribLocation(program, ATTR_POSITION, "Position");
        GL20.glBindAttribLocation(program, ATTR_NORMAL, "Normal");
        GL20.glBindAttribLocation(program, ATTR_UV, "UV0");
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new IllegalStateException("Edition de liens du shader " + fragment + " : " + log);
        }
        return new GlProgram(program, fragment.toString());
    }

    private static String read(ResourceManager resources, Identifier id) throws IOException {
        try (InputStream in = resources.getResourceOrThrow(id).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int compile(int type, String source, String label) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Compilation du shader " + label + " : " + log);
        }
        return shader;
    }

    public void use() {
        GL20.glUseProgram(id);
    }

    private int location(String uniform) {
        return locations.computeIfAbsent(uniform, u -> GL20.glGetUniformLocation(id, u));
    }

    public GlProgram set(String uniform, float v) {
        GL20.glUniform1f(location(uniform), v);
        return this;
    }

    public GlProgram set(String uniform, float x, float y) {
        GL20.glUniform2f(location(uniform), x, y);
        return this;
    }

    public GlProgram set(String uniform, float x, float y, float z) {
        GL20.glUniform3f(location(uniform), x, y, z);
        return this;
    }

    public GlProgram set(String uniform, Vector3d v) {
        GL20.glUniform3f(location(uniform), (float) v.x, (float) v.y, (float) v.z);
        return this;
    }

    public GlProgram setSampler(String uniform, int unit) {
        GL20.glUniform1i(location(uniform), unit);
        return this;
    }

    public GlProgram set(String uniform, Matrix4f m) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            m.get(buf);
            GL20.glUniformMatrix4fv(location(uniform), false, buf);
        }
        return this;
    }

    @Override
    public void close() {
        GL20.glDeleteProgram(id);
    }

    @Override
    public String toString() {
        return "GlProgram[" + name + "]";
    }
}
