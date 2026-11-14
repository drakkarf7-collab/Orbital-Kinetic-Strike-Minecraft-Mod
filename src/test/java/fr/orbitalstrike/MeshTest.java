package fr.orbitalstrike;

import fr.orbitalstrike.client.render.mesh.Icosphere;
import fr.orbitalstrike.client.render.mesh.MeshData;
import fr.orbitalstrike.client.render.mesh.ObjLoader;
import fr.orbitalstrike.client.render.mesh.TitleMesh;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MeshTest {
    @Test
    void icosphereIsAUnitSphereFacingOutward() {
        for (int subdivisions = 0; subdivisions <= 3; subdivisions++) {
            MeshData mesh = Icosphere.build(subdivisions);
            assertEquals(20 * (int) Math.pow(4, subdivisions), mesh.triangleCount());
            float[] v = mesh.vertices();
            for (int tri = 0; tri < mesh.triangleCount(); tri++) {
                int a = tri * 3 * MeshData.STRIDE, b = a + MeshData.STRIDE, c = b + MeshData.STRIDE;
                for (int i : new int[]{a, b, c}) {
                    double len = Math.sqrt(v[i] * v[i] + v[i + 1] * v[i + 1] + v[i + 2] * v[i + 2]);
                    assertEquals(1.0, len, 1e-5, "vertex on unit sphere");
                    assertEquals(v[i], v[i + 3], 1e-6, "normal equals position");
                }
                // Counter-clockwise from outside: (b - a) x (c - a) points outward.
                double ux = v[b] - v[a], uy = v[b + 1] - v[a + 1], uz = v[b + 2] - v[a + 2];
                double wx = v[c] - v[a], wy = v[c + 1] - v[a + 1], wz = v[c + 2] - v[a + 2];
                double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
                assertTrue(nx * v[a] + ny * v[a + 1] + nz * v[a + 2] > 0, "outward-facing triangle");
            }
        }
    }

    @Test
    void objLoaderReadsGroupsQuadsAndNormals() throws IOException {
        String obj = """
                # square in the XY plane, then a glowing triangle without normals
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                vt 0 0
                vt 1 1
                vn 0 0 1
                g body
                f 1/1/1 2/1/1 3/2/1 4/2/1
                g emissive_coil
                f -4 -3 -2
                """;
        Map<String, MeshData> groups = ObjLoader.parse(new StringReader(obj));
        assertEquals(2, groups.size());

        MeshData body = groups.get("body");
        assertEquals(2, body.triangleCount(), "quad splits into two triangles");
        float[] v = body.vertices();
        assertEquals(1f, v[5], "normal read from file");
        // Third vertex of the first triangle: corner (1, 1, 0) with uv (1, 1).
        int third = 2 * MeshData.STRIDE;
        assertArrayEquals(new float[]{1, 1, 0, 0, 0, 1, 1, 1},
                java.util.Arrays.copyOfRange(v, third, third + MeshData.STRIDE));

        MeshData glow = groups.get("emissive_coil");
        assertTrue(groups.keySet().stream().anyMatch(k -> k.startsWith(ObjLoader.EMISSIVE_PREFIX)));
        assertEquals(1, glow.triangleCount(), "negative indices relative to end");
        assertEquals(1f, glow.vertices()[5], 1e-6, "face normal computed when omitted");
    }

    @Test
    void surfaceOfRevolutionFollowsItsProfile() {
        // Plasma sheath paraboloid: radius = sqrt(x).
        MeshData mesh = fr.orbitalstrike.client.render.mesh.SurfaceOfRevolution.build(8, 12, Math::sqrt);
        assertEquals(8 * 12 * 2, mesh.triangleCount());
        float[] v = mesh.vertices();
        for (int i = 0; i < v.length; i += MeshData.STRIDE) {
            double x = v[i], radius = Math.sqrt(v[i + 1] * v[i + 1] + v[i + 2] * v[i + 2]);
            assertTrue(x >= 0 && x <= 1);
            assertEquals(Math.sqrt(x), radius, 1e-5, "radius follows profile");
            assertEquals(x, v[i + 6], 1e-6, "u equals position along axis");
            double len = Math.sqrt(v[i + 3] * v[i + 3] + v[i + 4] * v[i + 4] + v[i + 5] * v[i + 5]);
            assertEquals(1.0, len, 1e-4, "unit normal");
            if (x > 0.2) assertTrue(v[i + 3] < 0, "flaring surface normal points toward nose");
        }
    }

    @Test
    void objLoaderReportsTheFaultyLine() {
        IOException e = assertThrows(IOException.class, () -> ObjLoader.parse(new StringReader("v 0 0 0\nf 1 2 9\n")));
        assertTrue(e.getMessage().contains("line 2"));
    }

    @Test
    void titleMeshBuildsValidTitleTriangles() {
        MeshData mesh = TitleMesh.buildTitle();
        assertTrue(mesh.triangleCount() > 250, "KINETIC STRIKE mesh contains both lines and separator");
        float[] v = mesh.vertices();
        for (int i = 0; i < v.length; i += MeshData.STRIDE) {
            double len = Math.sqrt(v[i + 3] * v[i + 3] + v[i + 4] * v[i + 4] + v[i + 5] * v[i + 5]);
            assertEquals(1.0, len, 1e-4, "unit normal");
        }
    }
}
