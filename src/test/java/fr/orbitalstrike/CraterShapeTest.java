package fr.orbitalstrike;

import fr.orbitalstrike.crater.CraterProfile;
import fr.orbitalstrike.crater.CraterShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CraterShapeTest {
    private static final int PLANE = 64;

    @Test
    void profilesMatchSpec() {
        int[][] expected = {{12, 6}, {20, 9}, {32, 14}, {48, 20}, {64, 26}};
        for (int p = 1; p <= 5; p++) {
            CraterProfile prof = CraterProfile.forPower(p);
            assertEquals(expected[p - 1][0], prof.radius(), "radius power " + p);
            assertEquals(expected[p - 1][1], prof.maxDepth(), "depth power " + p);
        }
        assertEquals(1, CraterProfile.forPower(0).power());
        assertEquals(5, CraterProfile.forPower(99).power());
    }

    @Test
    void centralPeakRisesAboveTheMoat() {
        CraterProfile prof = CraterProfile.forPower(3);
        CraterShape s = new CraterShape(prof, PLANE, 42L);
        CraterShape.Column c = s.at(0, 0);
        assertEquals(CraterShape.Zone.BOWL, c.zone());
        // The central peak raises the center by 15% of max depth.
        int expectedCenter = PLANE - (int) Math.round(prof.maxDepth() * (1 - CraterShape.UPLIFT_HEIGHT));
        assertEquals(expectedCenter, c.surfaceY());

        int deepest = Integer.MAX_VALUE;
        for (int dx = -40; dx <= 40; dx++) {
            for (int dz = -40; dz <= 40; dz++) {
                deepest = Math.min(deepest, s.at(dx, dz).surfaceY());
            }
        }
        assertTrue(deepest < c.surfaceY(), "a deeper moat surrounds the central peak");
        assertTrue(deepest >= PLANE - prof.maxDepth() - 1, "never deeper than max depth");
    }

    @Test
    void bowlNeverAbovePlaneAndRimNeverBelow() {
        CraterShape s = new CraterShape(CraterProfile.forPower(5), PLANE, 7L);
        int e = s.scanExtent();
        boolean sawRim = false, sawPlane = false, sawOutside = false;
        for (int dx = -e; dx <= e; dx += 2) {
            for (int dz = -e; dz <= e; dz += 2) {
                CraterShape.Column c = s.at(dx, dz);
                switch (c.zone()) {
                    case BOWL -> assertTrue(c.surfaceY() <= PLANE);
                    case RIM -> {
                        sawRim = true;
                        assertTrue(c.surfaceY() >= PLANE && c.surfaceY() <= PLANE + 4);
                    }
                    case PLANE -> {
                        sawPlane = true;
                        assertTrue(c.surfaceY() > PLANE);
                    }
                    case OUTSIDE -> sawOutside = true;
                }
            }
        }
        assertTrue(sawRim && sawPlane && sawOutside);
    }

    @Test
    void wallsDescendInTerraces() {
        for (long seed = 0; seed < 20; seed++) {
            CraterShape s = new CraterShape(CraterProfile.forPower(5), PLANE, seed);
            assertTrue(s.terraceCount() == 2 || s.terraceCount() == 3);
            double angle = 0.3;
            // Depth never decreases when moving toward the center (outside central peak).
            double previous = -1;
            int flatSteps = 0, steps = 0;
            for (double n = 1.0; n >= 0.4; n -= 0.002) {
                double depth = s.depthAt(n, angle);
                assertTrue(depth >= previous - 1e-9, "monotonic depth at n=" + n);
                if (n > CraterShape.TERRACE_START) {
                    steps++;
                    if (depth - previous < 1e-6) flatSteps++;
                }
                previous = depth;
            }
            // A large portion of the wall consists of flat benches (terraces).
            assertTrue(flatSteps > steps * 0.4, "terrace benches (seed " + seed + ")");
            assertEquals(0.0, s.depthAt(1.0, angle), 1e-6);
        }
    }

    @Test
    void talusIsAGentleSlopeNotACliff() {
        CraterShape s = new CraterShape(CraterProfile.forPower(4), PLANE, 5L);
        int previous = -1;
        int first = -1, last = -1, firstX = 0, lastX = 0;
        for (int dx = 0; dx < s.scanExtent(); dx++) {
            CraterShape.Column c = s.at(dx, 0);
            if (c.zone() != CraterShape.Zone.PLANE) {
                previous = -1;
                continue;
            }
            if (previous >= 0) {
                assertTrue(Math.abs(c.surfaceY() - previous) <= 2, "no step larger than 2 blocks at dx=" + dx);
            }
            if (first < 0) {
                first = c.surfaceY();
                firstX = dx;
            }
            last = c.surfaceY();
            lastX = dx;
            previous = c.surfaceY();
        }
        assertTrue(first >= 0 && first <= PLANE + 2, "talus starts at rim level");
        double slope = (last - first) / (double) (lastX - firstX);
        assertEquals(1.0 / CraterShape.TALUS_RUN, slope, 0.05, "1 block rise per 1.5 horizontal blocks");
    }

    @Test
    void ejectaBlanketThinsOutAndHasRays() {
        CraterShape s = new CraterShape(CraterProfile.forPower(3), PLANE, 11L);
        assertTrue(s.rayCount() >= 8 && s.rayCount() <= 12);

        // Find one angle along a ray and one angle between rays.
        double rayAngle = 0, plainAngle = 0, best = -1, worst = 2;
        for (int i = 0; i < 3600; i++) {
            double a = Math.toRadians(i / 10.0) - Math.PI;
            double strength = s.rayStrength(a);
            if (strength > best) {
                best = strength;
                rayAngle = a;
            }
            if (strength < worst) {
                worst = strength;
                plainAngle = a;
            }
        }
        assertTrue(best > 0.7 && worst < 0.05);

        assertEquals(0.0, s.ejectaThickness(1.0, plainAngle), 1e-9, "no ejecta inside crater");
        double previous = Double.MAX_VALUE;
        for (double n = 1.3; n < 3.0; n += 0.05) {
            double t = s.ejectaThickness(n, plainAngle);
            assertTrue(t <= previous + 1e-9, "blanket thins out with distance");
            previous = t;
        }
        assertEquals(0.0, s.ejectaThickness(CraterShape.EJECTA_END, plainAngle), 1e-9, "blanket ends at 3 r");
        assertTrue(s.ejectaThickness(2.0, rayAngle) > 2 * s.ejectaThickness(2.0, plainAngle), "thicker ray");
        assertTrue(s.ejectaThickness(3.05, rayAngle) > 0, "rays extend beyond 3 r");
    }

    @Test
    void scorchFadesWithDistance() {
        CraterShape s = new CraterShape(CraterProfile.forPower(2), PLANE, 3L);
        assertEquals(1.0, s.scorchChance(1.0), 1e-9);
        assertTrue(s.scorchChance(1.5) > s.scorchChance(2.0));
        assertEquals(0.0, s.scorchChance(CraterShape.SCORCH_END), 1e-9);
        for (int i = 0; i < 1000; i++) {
            double v = s.noise(i, -i * 3, 1);
            assertTrue(v >= 0 && v < 1);
        }
    }

    @Test
    void edgeIsIrregularButBounded() {
        CraterShape s = new CraterShape(CraterProfile.forPower(3), PLANE, 123L);
        double min = Double.MAX_VALUE, max = 0;
        for (int i = 0; i < 360; i++) {
            double f = s.edgeFactor(Math.toRadians(i));
            min = Math.min(min, f);
            max = Math.max(max, f);
        }
        assertTrue(min >= 0.9 - 1e-9 && max <= 1.1 + 1e-9, "max +/-10% variation");
        assertTrue(max - min > 0.05, "rim edge is not a perfect circle");
    }

    @Test
    void sameSeedSameShape() {
        CraterShape a = new CraterShape(CraterProfile.forPower(4), PLANE, 99L);
        CraterShape b = new CraterShape(CraterProfile.forPower(4), PLANE, 99L);
        for (int dx = -50; dx <= 50; dx += 5) {
            for (int dz = -50; dz <= 50; dz += 5) {
                assertEquals(a.at(dx, dz), b.at(dx, dz));
            }
        }
    }

    @Test
    void fissureNetworkIsBoundedAndDeterministic() {
        CraterProfile prof = CraterProfile.forPower(3);
        CraterShape a = new CraterShape(prof, PLANE, 77L);
        CraterShape b = new CraterShape(prof, PLANE, 77L);
        int highCount = 0;
        int lowCount = 0;
        int e = a.scanExtent();
        for (int dx = -e; dx <= e; dx++) {
            for (int dz = -e; dz <= e; dz++) {
                double f = a.fissureStrength(dx, dz);
                assertTrue(f >= 0.0 && f <= 1.0, "fissure in [0, 1]");
                assertEquals(f, b.fissureStrength(dx, dz), 1e-9, "deterministic fissure");
                double n = Math.hypot(dx, dz) / (prof.radius() * a.edgeFactor(Math.atan2(dz, dx)));
                if (n >= 1.1 && n <= 2.0) {
                    if (f > 0.75) highCount++;
                    if (f < 0.25) lowCount++;
                }
                if (n > CraterShape.SCORCH_END + 0.05) {
                    assertEquals(0.0, f, 1e-9, "zero fissure beyond SCORCH_END");
                }
                double v = a.wallVeinStrength(dx, dz);
                assertTrue(v >= 0.0 && v <= 1.0, "wall vein in [0, 1]");
            }
        }
        assertTrue(highCount > 20, "glowing fissures present around crater");
        assertTrue(lowCount > 50, "dark crust plates present between fissures");
    }

    @Test
    void spireHeightIsMonumentalAndScalesWithPower() {
        int prev = 0;
        for (int p = 1; p <= 5; p++) {
            CraterProfile prof = CraterProfile.forPower(p);
            assertTrue(prof.spireHeight() > prev, "spire height increases with power");
            assertTrue(prof.spireRadius() >= 2, "shaft radius sufficient");
            prev = prof.spireHeight();
        }
        assertTrue(CraterProfile.forPower(1).spireHeight() >= 70);
        assertTrue(CraterProfile.forPower(5).spireHeight() >= 140);
    }
}
