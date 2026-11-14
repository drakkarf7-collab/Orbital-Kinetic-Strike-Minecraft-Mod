package fr.orbitalstrike.client.render.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * Extruded 3D mesh for the "KINETIC STRIKE" title displayed behind the gas giant.
 * Pure geometry with no OpenGL dependency (unit-testable in JUnit), centered on X = 0.
 */
public final class TitleMesh {
    private static final float HALF_Z = 0.12f;

    private TitleMesh() {}

    public static MeshData buildTitle() {
        List<Float> out = new ArrayList<>(8192);
        // Line 1: KINETIC (above the separator bar)
        addWord(out, new char[]{'K', 'I', 'N', 'E', 'T', 'I', 'C'}, 0.62f, 0.82f, 0.82f, 0.34f, 0.14f);
        // Thin separator bar between both lines
        addBox(out, -5.0f, 0.34f, -HALF_Z * 0.55f, 5.0f, 0.40f, HALF_Z * 0.55f);
        // Line 2: STRIKE (below the separator bar)
        addWord(out, new char[]{'S', 'T', 'R', 'I', 'K', 'E'}, -0.52f, 0.70f, 0.80f, 0.38f, 0.13f);

        float[] verts = new float[out.size()];
        for (int i = 0; i < verts.length; i++) verts[i] = out.get(i);
        return new MeshData(verts);
    }

    private static void addWord(List<Float> out, char[] letters, float y0, float h, float cellW, float gap, float stroke) {
        float totalW = letters.length * cellW + (letters.length - 1) * gap;
        float x = -totalW * 0.5f;
        for (char c : letters) {
            addLetter(out, c, x, y0, h, cellW, stroke);
            x += cellW + gap;
        }
    }

    private static void addLetter(List<Float> out, char ch, float x0, float y0, float h, float w, float s) {
        float x1 = x0 + w;
        float y1 = y0 + h;
        float midY = y0 + h * 0.5f;
        switch (ch) {
            case 'F' -> {
                addBox(out, x0, y0, -HALF_Z, x0 + s, y1, HALF_Z);
                addBox(out, x0, y1 - s, -HALF_Z, x1, y1, HALF_Z);
                addBox(out, x0 + s, midY - s * 0.45f, -HALF_Z, x1 - w * 0.16f, midY + s * 0.45f, HALF_Z);
            }
            case 'R' -> {
                addBox(out, x0, y0, -HALF_Z, x0 + s, y1, HALF_Z);
                addBox(out, x0 + s, y1 - s, -HALF_Z, x1 - s * 0.35f, y1, HALF_Z);
                addBox(out, x0 + s, midY - s * 0.45f, -HALF_Z, x1 - s * 0.35f, midY + s * 0.45f, HALF_Z);
                addBox(out, x1 - s, midY + s * 0.15f, -HALF_Z, x1, y1 - s * 0.35f, HALF_Z);
                addDiag(out, x0 + w * 0.45f, midY - s * 0.25f, x1 - s * 0.35f, y0 + s * 0.35f, s * 0.54f);
            }
            case 'A' -> {
                float apex = (x0 + x1) * 0.5f;
                addDiag(out, x0 + s * 0.45f, y0 + s * 0.3f, apex - s * 0.18f, y1 - s * 0.25f, s * 0.52f);
                addDiag(out, x1 - s * 0.45f, y0 + s * 0.3f, apex + s * 0.18f, y1 - s * 0.25f, s * 0.52f);
                addBox(out, apex - s * 0.7f, y1 - s, -HALF_Z, apex + s * 0.7f, y1, HALF_Z);
                addBox(out, x0 + w * 0.20f, y0 + h * 0.30f, -HALF_Z, x1 - w * 0.20f, y0 + h * 0.30f + s * 0.9f, HALF_Z);
            }
            case 'P' -> {
                addBox(out, x0, y0, -HALF_Z, x0 + s, y1, HALF_Z);
                addBox(out, x0 + s, y1 - s, -HALF_Z, x1 - s * 0.3f, y1, HALF_Z);
                addBox(out, x0 + s, midY - s * 0.45f, -HALF_Z, x1 - s * 0.3f, midY + s * 0.45f, HALF_Z);
                addBox(out, x1 - s, midY + s * 0.15f, -HALF_Z, x1, y1 - s * 0.3f, HALF_Z);
            }
            case 'E' -> {
                addBox(out, x0, y0, -HALF_Z, x0 + s, y1, HALF_Z);
                addBox(out, x0 + s, y1 - s, -HALF_Z, x1, y1, HALF_Z);
                addBox(out, x0 + s, midY - s * 0.45f, -HALF_Z, x1 - w * 0.18f, midY + s * 0.45f, HALF_Z);
                addBox(out, x0 + s, y0, -HALF_Z, x1, y0 + s, HALF_Z);
            }
            case 'C' -> {
                addBox(out, x0, y0 + s * 0.35f, -HALF_Z, x0 + s, y1 - s * 0.35f, HALF_Z);
                addBox(out, x0 + s * 0.35f, y1 - s, -HALF_Z, x1, y1, HALF_Z);
                addBox(out, x0 + s * 0.35f, y0, -HALF_Z, x1, y0 + s, HALF_Z);
            }
            case 'I' -> {
                float cx = (x0 + x1) * 0.5f;
                addBox(out, x0 + s * 0.55f, y1 - s * 0.85f, -HALF_Z, x1 - s * 0.55f, y1, HALF_Z);
                addBox(out, cx - s * 0.55f, y0, -HALF_Z, cx + s * 0.55f, y1, HALF_Z);
                addBox(out, x0 + s * 0.55f, y0, -HALF_Z, x1 - s * 0.55f, y0 + s * 0.85f, HALF_Z);
            }
            case 'N' -> {
                addBox(out, x0, y0, -HALF_Z, x0 + s, y1, HALF_Z);
                addBox(out, x1 - s, y0, -HALF_Z, x1, y1, HALF_Z);
                addDiag(out, x0 + s * 0.55f, y1 - s * 0.45f, x1 - s * 0.55f, y0 + s * 0.45f, s * 0.48f);
            }
            case 'T' -> {
                float cx = (x0 + x1) * 0.5f;
                addBox(out, x0, y1 - s, -HALF_Z, x1, y1, HALF_Z);
                addBox(out, cx - s * 0.55f, y0, -HALF_Z, cx + s * 0.55f, y1 - s, HALF_Z);
            }
            case 'Q' -> {
                addBox(out, x0, y0 + s * 0.35f, -HALF_Z, x0 + s, y1 - s * 0.35f, HALF_Z);
                addBox(out, x1 - s, y0 + s * 0.35f, -HALF_Z, x1, y1 - s * 0.35f, HALF_Z);
                addBox(out, x0 + s * 0.35f, y1 - s, -HALF_Z, x1 - s * 0.35f, y1, HALF_Z);
                addBox(out, x0 + s * 0.35f, y0, -HALF_Z, x1 - s * 0.35f, y0 + s, HALF_Z);
                addDiag(out, x0 + w * 0.56f, y0 + h * 0.28f, x1 - s * 0.05f, y0 - s * 0.32f, s * 0.42f);
            }
            case 'U' -> {
                addBox(out, x0, y0 + s * 0.35f, -HALF_Z, x0 + s, y1, HALF_Z);
                addBox(out, x1 - s, y0 + s * 0.35f, -HALF_Z, x1, y1, HALF_Z);
                addBox(out, x0 + s * 0.35f, y0, -HALF_Z, x1 - s * 0.35f, y0 + s, HALF_Z);
            }
            case 'K' -> {
                addBox(out, x0, y0, -HALF_Z, x0 + s, y1, HALF_Z);
                addDiag(out, x0 + s * 0.8f, midY, x1 - s * 0.35f, y1 - s * 0.35f, s * 0.52f);
                addDiag(out, x0 + s * 0.8f, midY, x1 - s * 0.35f, y0 + s * 0.35f, s * 0.52f);
            }
            case 'S' -> {
                addBox(out, x0 + s * 0.35f, y1 - s, -HALF_Z, x1, y1, HALF_Z);
                addBox(out, x0, midY + s * 0.05f, -HALF_Z, x0 + s, y1 - s * 0.35f, HALF_Z);
                addBox(out, x0 + s * 0.35f, midY - s * 0.45f, -HALF_Z, x1 - s * 0.35f, midY + s * 0.45f, HALF_Z);
                addBox(out, x1 - s, y0 + s * 0.35f, -HALF_Z, x1, midY - s * 0.05f, HALF_Z);
                addBox(out, x0, y0, -HALF_Z, x1 - s * 0.35f, y0 + s, HALF_Z);
            }
            default -> {}
        }
    }

    private static void addBox(List<Float> out, float x0, float y0, float z0, float x1, float y1, float z1) {
        addQuad(out, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1);
        addQuad(out, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1);
        addQuad(out, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0);
        addQuad(out, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0);
        addQuad(out, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0);
        addQuad(out, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0);
    }

    private static void addDiag(List<Float> out, float ax, float ay, float bx, float by, float halfW) {
        float dx = bx - ax, dy = by - ay;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-5f) return;
        float tx = dx / len, ty = dy / len;
        float nx = -ty, ny = tx;
        float p0x = ax + nx * halfW, p0y = ay + ny * halfW;
        float p1x = ax - nx * halfW, p1y = ay - ny * halfW;
        float p2x = bx - nx * halfW, p2y = by - ny * halfW;
        float p3x = bx + nx * halfW, p3y = by + ny * halfW;
        addQuad(out, p1x, p1y, HALF_Z, p2x, p2y, HALF_Z, p3x, p3y, HALF_Z, p0x, p0y, HALF_Z, 0, 0, 1);
        addQuad(out, p0x, p0y, -HALF_Z, p3x, p3y, -HALF_Z, p2x, p2y, -HALF_Z, p1x, p1y, -HALF_Z, 0, 0, -1);
        addQuad(out, p0x, p0y, -HALF_Z, p0x, p0y, HALF_Z, p3x, p3y, HALF_Z, p3x, p3y, -HALF_Z, nx, ny, 0);
        addQuad(out, p2x, p2y, -HALF_Z, p2x, p2y, HALF_Z, p1x, p1y, HALF_Z, p1x, p1y, -HALF_Z, -nx, -ny, 0);
        addQuad(out, p1x, p1y, -HALF_Z, p1x, p1y, HALF_Z, p0x, p0y, HALF_Z, p0x, p0y, -HALF_Z, -tx, -ty, 0);
        addQuad(out, p3x, p3y, -HALF_Z, p3x, p3y, HALF_Z, p2x, p2y, HALF_Z, p2x, p2y, -HALF_Z, tx, ty, 0);
    }

    private static void addQuad(List<Float> out,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3,
                                float nx, float ny, float nz) {
        addVert(out, x0, y0, z0, nx, ny, nz, 0f, 0f);
        addVert(out, x1, y1, z1, nx, ny, nz, 1f, 0f);
        addVert(out, x2, y2, z2, nx, ny, nz, 1f, 1f);
        addVert(out, x0, y0, z0, nx, ny, nz, 0f, 0f);
        addVert(out, x2, y2, z2, nx, ny, nz, 1f, 1f);
        addVert(out, x3, y3, z3, nx, ny, nz, 0f, 1f);
    }

    private static void addVert(List<Float> out, float x, float y, float z, float nx, float ny, float nz, float u, float v) {
        out.add(x);
        out.add(y);
        out.add(z);
        out.add(nx);
        out.add(ny);
        out.add(nz);
        out.add(u);
        out.add(v);
    }
}
