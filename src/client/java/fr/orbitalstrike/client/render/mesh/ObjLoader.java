package fr.orbitalstrike.client.render.mesh;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wavefront OBJ file loader (Blockbench export format).
 * Supports v, vt, vn, groups (g or o), and faces f with 3+ vertices
 * (fan-triangulated), including v, v/vt, v//vn, and v/vt/vn forms.
 * Returns one mesh per group: groups prefixed with {@link #EMISSIVE_PREFIX}
 * are drawn with the emissive shader (feeding into bloom).
 * No OpenGL calls here: testable in JUnit.
 */
public final class ObjLoader {
    public static final String EMISSIVE_PREFIX = "emissive_";
    private static final String DEFAULT_GROUP = "default";

    private ObjLoader() {}

    public static Map<String, MeshData> parse(Reader reader) throws IOException {
        List<float[]> positions = new ArrayList<>(), normals = new ArrayList<>(), uvs = new ArrayList<>();
        Map<String, List<Float>> groups = new LinkedHashMap<>();
        String group = DEFAULT_GROUP;
        BufferedReader in = new BufferedReader(reader);
        String line;
        int lineNumber = 0;
        while ((line = in.readLine()) != null) {
            lineNumber++;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            try {
                switch (parts[0]) {
                    case "v" -> positions.add(floats(parts, 3));
                    case "vn" -> normals.add(floats(parts, 3));
                    case "vt" -> uvs.add(floats(parts, 2));
                    case "g", "o" -> group = parts.length > 1 ? parts[1] : DEFAULT_GROUP;
                    case "f" -> {
                        List<Float> out = groups.computeIfAbsent(group, k -> new ArrayList<>());
                        float[][] corners = new float[parts.length - 1][];
                        boolean[] hasNormal = new boolean[parts.length - 1];
                        for (int i = 1; i < parts.length; i++) {
                            corners[i - 1] = corner(parts[i], positions, normals, uvs);
                            hasNormal[i - 1] = declaresNormal(parts[i]);
                        }
                        for (int i = 1; i + 1 < corners.length; i++) {
                            // Any corner without an explicit normal gets the geometric triangle normal.
                            float[] flat = faceNormal(corners[0], corners[i], corners[i + 1]);
                            add(out, corners[0], hasNormal[0] ? null : flat);
                            add(out, corners[i], hasNormal[i] ? null : flat);
                            add(out, corners[i + 1], hasNormal[i + 1] ? null : flat);
                        }
                    }
                    default -> { /* mtllib, usemtl, s...: ignored */ }
                }
            } catch (RuntimeException e) {
                throw new IOException("Invalid OBJ on line " + lineNumber + ": " + line, e);
            }
        }
        Map<String, MeshData> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Float>> e : groups.entrySet()) {
            float[] data = new float[e.getValue().size()];
            for (int i = 0; i < data.length; i++) data[i] = e.getValue().get(i);
            result.put(e.getKey(), new MeshData(data));
        }
        return result;
    }

    private static float[] floats(String[] parts, int count) {
        float[] out = new float[count];
        for (int i = 0; i < count; i++) out[i] = Float.parseFloat(parts[i + 1]);
        return out;
    }

    /** Face corner: position (3), normal (3), uv (2). OBJ indices are 1-based; negative = relative to end. */
    private static float[] corner(String token, List<float[]> positions, List<float[]> normals, List<float[]> uvs) {
        String[] idx = token.split("/", -1);
        float[] out = new float[MeshData.STRIDE];
        System.arraycopy(pick(positions, idx[0]), 0, out, 0, 3);
        if (idx.length > 1 && !idx[1].isEmpty()) System.arraycopy(pick(uvs, idx[1]), 0, out, 6, 2);
        if (idx.length > 2 && !idx[2].isEmpty()) System.arraycopy(pick(normals, idx[2]), 0, out, 3, 3);
        return out;
    }

    private static boolean declaresNormal(String token) {
        String[] idx = token.split("/", -1);
        return idx.length > 2 && !idx[2].isEmpty();
    }

    private static float[] pick(List<float[]> list, String index) {
        int i = Integer.parseInt(index);
        return list.get(i > 0 ? i - 1 : list.size() + i);
    }

    private static float[] faceNormal(float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        return len < 1e-12f ? new float[]{0, 1, 0} : new float[]{nx / len, ny / len, nz / len};
    }

    private static void add(List<Float> out, float[] corner, float[] flatNormal) {
        for (int i = 0; i < MeshData.STRIDE; i++) {
            boolean normalSlot = i >= 3 && i < 6;
            out.add(normalSlot && flatNormal != null ? flatNormal[i - 3] : corner[i]);
        }
    }
}
