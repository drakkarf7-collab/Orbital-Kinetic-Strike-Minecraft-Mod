package fr.orbitalstrike.strike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Precomputed list of strike phases and their effective durations,
 * based on the selected cinematic speed and mode.
 * No Minecraft dependencies: JUnit-testable.
 */
public final class StrikeTimeline {
    /** A phase and its effective duration in ticks (0 = until crater completion). */
    public record Entry(StrikePhase phase, int ticks) {}

    /** Duration of the descent shot in short cinematic mode. */
    public static final int SHORT_FLIGHT_TICKS = 82;

    private final List<Entry> entries;

    private StrikeTimeline(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    public static StrikeTimeline build(CinematicMode mode, float speed) {
        float s = clampSpeed(speed);
        List<Entry> list = new ArrayList<>();
        for (StrikePhase p : StrikePhase.values()) {
            if (p == StrikePhase.DONE) continue;
            if (p.cinematic) {
                if (mode == CinematicMode.OFF) continue;
                if (mode == CinematicMode.SHORT) {
                    if (p != StrikePhase.FLIGHT) continue;
                    list.add(new Entry(p, scaled(SHORT_FLIGHT_TICKS, s)));
                    continue;
                }
            }
            int ticks = switch (p) {
                case IMPACT -> 1;           // instantaneous, independent of speed
                case SETTLE -> 0;           // waits until crater completion
                default -> scaled(p.baseTicks, s);
            };
            list.add(new Entry(p, ticks));
        }
        return new StrikeTimeline(list);
    }

    public static float clampSpeed(float speed) {
        if (Float.isNaN(speed)) return 1f;
        return Math.max(0.5f, Math.min(2f, speed));
    }

    static int scaled(int base, float speed) {
        return Math.max(1, Math.round(base / speed));
    }

    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public Entry get(int i) {
        return entries.get(i);
    }

    public boolean hasCinematic() {
        return entries.stream().anyMatch(e -> e.phase().cinematic);
    }

    /** Sum of fixed phase durations up to and including impact. */
    public int ticksUntilImpact() {
        int t = 0;
        for (Entry e : entries) {
            t += e.ticks();
            if (e.phase() == StrikePhase.IMPACT) break;
        }
        return t;
    }
}
