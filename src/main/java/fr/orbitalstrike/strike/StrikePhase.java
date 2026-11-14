package fr.orbitalstrike.strike;

/**
 * Strike phases with their base duration in ticks (at 1x speed).
 * Shared between server (timeline) and client (rendering).
 * No Minecraft dependencies: JUnit-testable.
 */
public enum StrikePhase {
    MARK(38, false),
    COUNTDOWN(96, false),
    ASCENT(104, true),
    /** Jump toward the gas giant: hyperspace tunnel followed by arrival at the planet. */
    TRANSFER(108, true),
    RING(120, true),
    LOAD(88, true),
    SPIN(152, true),
    RELEASE(82, true),
    FLIGHT(124, true),
    /** Rod descends vertically through the sky above the target (visible from the ground to everyone). */
    STREAK(18, false),
    HUSH(2, false),
    IMPACT(1, false),
    SHOCKWAVE(96, false),
    SETTLE(0, false),
    DONE(0, false);

    /** Base duration in ticks at 1x speed (0 = variable duration). */
    public final int baseTicks;
    /** True if this phase plays inside the space cinematic. */
    public final boolean cinematic;

    StrikePhase(int baseTicks, boolean cinematic) {
        this.baseTicks = baseTicks;
        this.cinematic = cinematic;
    }

    private static final StrikePhase[] VALUES = values();

    public static StrikePhase byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : DONE;
    }
}
