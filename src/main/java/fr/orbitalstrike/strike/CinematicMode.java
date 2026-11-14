package fr.orbitalstrike.strike;

/** Cinematic mode selected by the player in the menu. */
public enum CinematicMode {
    FULL, SHORT, OFF;

    private static final CinematicMode[] VALUES = values();

    public static CinematicMode byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : FULL;
    }

    public CinematicMode next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }
}
