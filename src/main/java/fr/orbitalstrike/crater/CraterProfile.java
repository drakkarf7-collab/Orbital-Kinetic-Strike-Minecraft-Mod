package fr.orbitalstrike.crater;

/**
 * Crater dimensions by power level (1 to 5).
 * No Minecraft dependency: shared between client and server, and unit-testable.
 */
public record CraterProfile(int power, int radius, int maxDepth, int rimHeight) {
    private static final int[] RADIUS = {12, 20, 32, 48, 64};
    private static final int[] DEPTH = {6, 9, 14, 20, 26};
    private static final int[] RIM = {1, 2, 2, 3, 3};

    public static CraterProfile forPower(int power) {
        int i = Math.max(1, Math.min(5, power)) - 1;
        return new CraterProfile(i + 1, RADIUS[i], DEPTH[i], RIM[i]);
    }

    /** Actual radius of the central basin carved by {@link ImpactCraterGenerator} for the given power. */
    public static int basinRadius(int power) {
        int p = Math.max(1, Math.min(5, power));
        return (int) Math.round(88.0 * Math.max(0.25, p / 4.0));
    }

    /** Outer rim radius (1.25 r). */
    public double rimOuter() {
        return radius * CraterShape.RIM_END;
    }

    /** Scorched zone radius (2.5 r). */
    public double scorchOuter() {
        return radius * CraterShape.SCORCH_END;
    }

    /** Height of the tungsten spire above the impact plane. */
    public int spireHeight() {
        return 48 + maxDepth * 4;
    }

    /** Radius of the tungsten spire shaft in blocks. */
    public int spireRadius() {
        return power <= 2 ? 2 : 3;
    }
}
