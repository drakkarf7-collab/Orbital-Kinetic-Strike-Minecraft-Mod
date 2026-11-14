package fr.orbitalstrike;

import fr.orbitalstrike.strike.CinematicMode;
import fr.orbitalstrike.strike.StrikePhase;
import fr.orbitalstrike.strike.StrikeTimeline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StrikeTimelineTest {
    private static List<StrikePhase> phases(StrikeTimeline t) {
        return t.entries().stream().map(StrikeTimeline.Entry::phase).toList();
    }

    @Test
    void fullModeHasAllPhasesInOrder() {
        StrikeTimeline t = StrikeTimeline.build(CinematicMode.FULL, 1f);
        assertEquals(List.of(StrikePhase.MARK, StrikePhase.COUNTDOWN, StrikePhase.ASCENT, StrikePhase.TRANSFER, StrikePhase.RING,
                StrikePhase.LOAD, StrikePhase.SPIN, StrikePhase.RELEASE, StrikePhase.FLIGHT, StrikePhase.STREAK,
                StrikePhase.HUSH, StrikePhase.IMPACT, StrikePhase.SHOCKWAVE, StrikePhase.SETTLE), phases(t));
        // (38+96+104+108+120+88+152+82+124+18+2) = 932 ticks + 1 impact tick
        assertEquals(933, t.ticksUntilImpact());
        assertTrue(t.hasCinematic());
    }

    @Test
    void shortModeKeepsOnlyFlight() {
        StrikeTimeline t = StrikeTimeline.build(CinematicMode.SHORT, 1f);
        assertEquals(List.of(StrikePhase.MARK, StrikePhase.COUNTDOWN, StrikePhase.FLIGHT, StrikePhase.STREAK,
                StrikePhase.HUSH, StrikePhase.IMPACT, StrikePhase.SHOCKWAVE, StrikePhase.SETTLE), phases(t));
        assertEquals(StrikeTimeline.SHORT_FLIGHT_TICKS, t.get(2).ticks());
    }

    @Test
    void offModeHasNoCinematic() {
        StrikeTimeline t = StrikeTimeline.build(CinematicMode.OFF, 1f);
        assertFalse(t.hasCinematic());
        assertFalse(phases(t).contains(StrikePhase.FLIGHT));
        assertTrue(phases(t).contains(StrikePhase.HUSH));
    }

    @Test
    void speedScalesDurationsButNotImpact() {
        StrikeTimeline slow = StrikeTimeline.build(CinematicMode.FULL, 0.5f);
        StrikeTimeline fast = StrikeTimeline.build(CinematicMode.FULL, 2f);
        assertEquals(192, slow.get(1).ticks()); // 96-tick countdown / 0.5
        assertEquals(48, fast.get(1).ticks());
        int impactIdx = phases(fast).indexOf(StrikePhase.IMPACT);
        assertEquals(1, fast.get(impactIdx).ticks());
        assertEquals(1, slow.get(impactIdx).ticks());
    }

    @Test
    void speedIsClamped() {
        assertEquals(0.5f, StrikeTimeline.clampSpeed(0.01f));
        assertEquals(2f, StrikeTimeline.clampSpeed(50f));
        assertEquals(1f, StrikeTimeline.clampSpeed(Float.NaN));
    }
}
