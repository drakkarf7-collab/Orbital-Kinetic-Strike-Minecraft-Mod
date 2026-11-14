package fr.orbitalstrike.crater;

/**
 * Pure crater geometry relative to the impact plane (planeY).
 * <ul>
 *   <li>Parabolic bowl with an irregular rim (±10% by angle)</li>
 *   <li>Terraces: beyond 0.7 r, the wall steps down in 2 or 3 collapse benches</li>
 *   <li>Central peak: Gaussian uplift (≈ 0.15 × depth) supporting the spire</li>
 *   <li>Rim between 1 and 1.25 r</li>
 *   <li>Talus: beyond the rim, higher terrain is trimmed at a gentle slope
 *       (1 vertical block per 1.5 horizontal blocks)</li>
 *   <li>Ejecta blanket from 1.25 r to 3 r, with longer bright rays</li>
 *   <li>Scorched zone extending to 2.5 r</li>
 * </ul>
 * No Minecraft dependencies: JUnit-testable.
 */
public final class CraterShape {
    public static final double RIM_END = 1.25;
    /** Start of terraces (normalized distance). */
    public static final double TERRACE_START = 0.7;
    /** Talus slope: horizontal blocks per vertical block. */
    public static final double TALUS_RUN = 1.5;
    /** Maximum terrain height the talus slope can cut into. */
    public static final int TALUS_MAX_RISE = 40;
    /** Central peak height as a fraction of max depth. */
    public static final double UPLIFT_HEIGHT = 0.15;
    /** Central peak width (standard deviation) as a fraction of radius. */
    public static final double UPLIFT_SIGMA = 0.12;
    public static final double EJECTA_END = 3.0;
    public static final double SCORCH_END = 2.5;
    public static final double TREE_FELL_START = 1.25;

    public enum Zone { BOWL, RIM, PLANE, OUTSIDE }

    /**
     * Per-column shape sample.
     * @param surfaceY target height (BOWL, RIM) or maximum allowed height (PLANE)
     * @param n normalized distance to center (1 = bowl rim)
     */
    public record Column(Zone zone, int surfaceY, double n) {}

    private final CraterProfile profile;
    private final int planeY;
    private final double p1, p2, p3, p4, p5;
    private final long seed;
    private final int terraces;
    private final double[] rayAngles;
    private final double[] rayReach;

    public CraterShape(CraterProfile profile, int planeY, long seed) {
        this.profile = profile;
        this.planeY = planeY;
        this.seed = seed;
        java.util.Random r = new java.util.Random(seed);
        this.p1 = r.nextDouble() * Math.PI * 2;
        this.p2 = r.nextDouble() * Math.PI * 2;
        this.p3 = r.nextDouble() * Math.PI * 2;
        this.p4 = r.nextDouble() * Math.PI * 2;
        this.p5 = r.nextDouble() * Math.PI * 2;
        this.terraces = 2 + r.nextInt(2);
        int rays = 8 + r.nextInt(5);
        this.rayAngles = new double[rays];
        this.rayReach = new double[rays];
        for (int i = 0; i < rays; i++) {
            // Evenly spaced with random jitter so no two rays overlap.
            rayAngles[i] = (i + 0.15 + 0.7 * r.nextDouble()) / rays * Math.PI * 2 - Math.PI;
            rayReach[i] = 0.75 + 0.25 * r.nextDouble();
        }
    }

    public CraterProfile profile() {
        return profile;
    }

    public int planeY() {
        return planeY;
    }

    public int terraceCount() {
        return terraces;
    }

    public int rayCount() {
        return rayAngles.length;
    }

    /** Angular radius factor between 0.9 and 1.1. */
    public double edgeFactor(double angle) {
        double v = 0.45 * Math.sin(3 * angle + p1)
                + 0.30 * Math.sin(5 * angle + p2)
                + 0.15 * Math.sin(11 * angle + p3)
                + 0.10 * Math.sin(17 * angle + p4);
        return 1.0 + 0.10 * v;
    }

    /** Small deterministic surface roughness (-1, 0, or +1 block). */
    public int roughness(int dx, int dz) {
        int v = (int) Math.floorMod(hash(dx, dz), 7);
        return v == 0 ? -1 : (v == 6 ? 1 : 0);
    }

    /** Deterministic pseudo-random value in [0, 1) for a column and noise channel. */
    public double noise(int dx, int dz, int channel) {
        long h = hash(dx * 31 + channel * 7919, dz * 17 - channel * 104729);
        return (h >>> 11) * 0x1.0p-53;
    }

    private long hash(int dx, int dz) {
        long h = seed ^ (dx * 0x9E3779B97F4A7C15L) ^ (dz * 0xC2B2AE3D27D4EB4FL);
        h ^= (h >>> 31);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 29);
        return h;
    }

    /** Depth below the impact plane for normalized distance n <= 1 and a given angle. */
    public double depthAt(double n, double angle) {
        double shaped = n;
        if (n > TERRACE_START) {
            // u: 0 at rim, 1 at terrace start. Steps wobble slightly with angle.
            double u = (1.0 - n) / (1.0 - TERRACE_START);
            double wobble = 0.05 * Math.sin(7 * angle + p5) + 0.03 * Math.sin(13 * angle + p2);
            double ut = Math.max(0, Math.min(1, u + wobble * Math.sin(Math.PI * u))) * terraces;
            int k = (int) Math.floor(ut);
            double f = ut - k;
            // Flat bench over 60% of each step, then collapse slope.
            double ramp = f < 0.6 ? 0 : smooth((f - 0.6) / 0.4);
            double stepped = Math.min(terraces, k + ramp) / terraces;
            shaped = 1.0 - (1.0 - TERRACE_START) * stepped;
        }
        double bowl = profile.maxDepth() * (1.0 - shaped * shaped);
        double g = n / UPLIFT_SIGMA;
        double uplift = profile.maxDepth() * UPLIFT_HEIGHT * Math.exp(-g * g);
        return Math.max(0, bowl - uplift);
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    public Column at(int dx, int dz) {
        double d = Math.sqrt((double) dx * dx + (double) dz * dz);
        double angle = Math.atan2(dz, dx);
        double r = profile.radius() * edgeFactor(angle);
        double n = d / r;

        if (n <= 1.0) {
            double depth = depthAt(n, angle);
            boolean rough = n > 0.3 && n < TERRACE_START;
            int y = planeY - (int) Math.round(depth) + (rough ? roughness(dx, dz) : 0);
            return new Column(Zone.BOWL, Math.min(y, planeY), n);
        }
        if (n <= RIM_END) {
            double t = (n - 1.0) / (RIM_END - 1.0);
            double bump = Math.sin(Math.PI * t) * profile.rimHeight();
            int y = planeY + (int) Math.round(bump) + roughness(dx, dz);
            return new Column(Zone.RIM, Math.max(y, planeY), n);
        }
        double beyond = d - r * RIM_END;
        if (beyond <= TALUS_MAX_RISE * TALUS_RUN) {
            // Talus: allowed height rises by 1 block every 1.5 horizontal blocks, with slight noise.
            int rise = (int) Math.floor(beyond / TALUS_RUN + 0.6 * noise(dx, dz, 3));
            return new Column(Zone.PLANE, planeY + 1 + rise, n);
        }
        return new Column(Zone.OUTSIDE, Integer.MAX_VALUE, n);
    }

    /**
     * Strength of the ejecta ray closest to this angle (0 = outside ray, 1 = on axis),
     * scaled by the ray's own reach.
     */
    public double rayStrength(double angle) {
        double best = 0;
        for (int i = 0; i < rayAngles.length; i++) {
            double da = Math.abs(wrap(angle - rayAngles[i]));
            double s = Math.exp(-(da * da) / (2 * 0.045 * 0.045)) * rayReach[i];
            if (s > best) best = s;
        }
        return best;
    }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    /**
     * Ejecta blanket thickness (in blocks; values below 1 act as a placement probability).
     * e(d) = e0 * (r/d)^3 from 1.25 r to 3 r; bright rays extend further.
     */
    public double ejectaThickness(double n, double angle) {
        if (n <= RIM_END) return 0;
        double ray = rayStrength(angle);
        double end = EJECTA_END * (1.0 + 0.35 * ray);
        if (n >= end) return 0;
        double e0 = profile.rimHeight() + 1.0;
        double base = e0 / (n * n * n);
        double fade = 1.0 - smooth((n - 0.8 * end) / (0.2 * end));
        return (base * (1.0 + 1.5 * ray) + 0.5 * ray) * fade;
    }

    /** Probability that a column is scorched: 1 at the crater rim, 0 at 2.5 r. */
    public double scorchChance(double n) {
        if (n >= SCORCH_END) return 0;
        if (n <= 1.0) return 1;
        return 1.0 - (n - 1.0) / (SCORCH_END - 1.0);
    }

    /**
     * Intensity of the incandescent fissure network around the crater ([0, 1]).
     * Combines sinuous radial fissures, a cellular fractured-crust network,
     * and a concentric fracture ring outside the rim up to {@link #SCORCH_END}.
     */
    public double fissureStrength(int dx, int dz) {
        double d = Math.hypot(dx, dz);
        if (d < 1.0) return 1.0;
        double angle = Math.atan2(dz, dx);
        double r = profile.radius() * edgeFactor(angle);
        double n = d / r;
        if (n >= SCORCH_END) return 0.0;

        // 1. Sinuous radial fissures (~1.5 blocks wide regardless of scale).
        double warpAng = 0.18 * Math.sin(d * 0.14 + p1) + 0.09 * Math.cos(d * 0.29 + p3);
        double arcDist = d * Math.abs(Math.sin((angle + warpAng) * 5.0 + p2)) / 5.0;
        double radialCrack = clamp01(1.0 - arcDist / 1.45);
        radialCrack *= radialCrack;

        // 2. Cellular network (Voronoi F2 - F1) forming fractured crust plates.
        double cell = 11.5;
        double wx = (dx + 2.1 * Math.sin(dz * 0.16 + p4) + 1.1 * Math.cos(dx * 0.23 + p2)) / cell;
        double wz = (dz + 2.1 * Math.cos(dx * 0.16 + p5) + 1.1 * Math.sin(dz * 0.23 + p1)) / cell;
        int gx = (int) Math.floor(wx);
        int gz = (int) Math.floor(wz);
        double f1 = Double.MAX_VALUE;
        double f2 = Double.MAX_VALUE;
        for (int ix = -1; ix <= 1; ix++) {
            for (int iz = -1; iz <= 1; iz++) {
                int cx = gx + ix;
                int cz = gz + iz;
                double px = cx + 0.15 + 0.70 * noise(cx, cz, 71);
                double pz = cz + 0.15 + 0.70 * noise(cx, cz, 83);
                double dist = Math.hypot(wx - px, wz - pz);
                if (dist < f1) {
                    f2 = f1;
                    f1 = dist;
                } else if (dist < f2) {
                    f2 = dist;
                }
            }
        }
        double cellCrack = clamp01(1.0 - (f2 - f1) / 0.20);
        cellCrack *= cellCrack;

        // 3. Concentric fracture near the rim.
        double ringCrack = Math.exp(-Math.pow((n - 1.08) / 0.06, 2.0)) * 0.82;

        double fade = n <= 1.05 ? 1.0 : smooth((SCORCH_END - n) / (SCORCH_END - 1.05));
        return clamp01(Math.max(radialCrack, Math.max(cellCrack * 0.95, ringCrack)) * fade);
    }

    /**
     * Intensity of incandescent veins along the terraced crater walls ([0, 1]).
     */
    public double wallVeinStrength(int dx, int dz) {
        double d = Math.hypot(dx, dz);
        if (d < 1.0) return 1.0;
        double angle = Math.atan2(dz, dx);
        double r = profile.radius() * edgeFactor(angle);
        double n = d / r;
        if (n < 0.12 || n > 1.05) return 0.0;

        double warp = 0.18 * Math.sin(d * 0.22 + p4) + 0.08 * Math.cos(angle * 7.0 + p1);
        double vein = Math.pow(Math.max(0.0, Math.cos((angle + warp) * 9.0)), 6.0);
        double terraceBand = Math.pow(Math.max(0.0, Math.sin(n * terraces * Math.PI)), 4.0) * 0.60;
        return clamp01(Math.max(vein, terraceBand) * (1.0 - 0.30 * n));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Half-width of the square scan area (margin for noisy rim and rays). */
    public int scanExtent() {
        double dressing = profile.radius() * 1.1 * EJECTA_END * 1.35;
        double talus = profile.radius() * 1.1 * RIM_END + TALUS_MAX_RISE * TALUS_RUN;
        return (int) Math.ceil(Math.max(dressing, talus)) + 1;
    }
}
