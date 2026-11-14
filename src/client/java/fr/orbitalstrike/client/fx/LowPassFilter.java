package fr.orbitalstrike.client.fx;

import fr.orbitalstrike.OrbitalStrike;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * True audio muffling during the hush phase: an OpenAL low-pass filter (EFX extension)
 * applied to all game sound sources. High frequencies drop out while lows remain,
 * creating genuine muffling rather than simple volume attenuation.
 * <p>Sources are tracked via {@code SourceMixin}. If the extension is absent or any call
 * fails, the filter disables itself and {@link SoundDirector} falls back to v1 volume attenuation.</p>
 */
public final class LowPassFilter {
    /** Overall gain and high-frequency gain when fully muffled. */
    private static final float MUFFLED_GAIN = 0.55f, MUFFLED_GAIN_HF = 0.015f;
    private static final float EPSILON = 0.001f;

    private static final Set<Integer> SOURCES = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> EXEMPT = ConcurrentHashMap.newKeySet();
    private static int filter = 0;
    private static boolean unavailable = false;
    private static float applied = 0f;

    private LowPassFilter() {}

    public static void track(int source) {
        SOURCES.add(source);
    }

    public static void untrack(int source) {
        SOURCES.remove(source);
        EXEMPT.remove(source);
    }

    /** Excludes a source (mod sound) from the low-pass filter and removes any active filter. */
    public static void exempt(int source) {
        EXEMPT.add(source);
        try {
            if (!unavailable && AL10.alIsSource(source)) {
                AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
            }
        } catch (Throwable ignored) {}
    }

    /** True if the filter could be created (EFX extension present). */
    public static boolean available() {
        return !unavailable && ensureFilter();
    }

    private static boolean ensureFilter() {
        if (unavailable) return false;
        try {
            if (filter != 0 && EXTEfx.alIsFilter(filter)) return true;
            long context = ALC10.alcGetCurrentContext();
            if (context == 0L || !ALC10.alcIsExtensionPresent(ALC10.alcGetContextsDevice(context), "ALC_EXT_EFX")) {
                unavailable = true;
                return false;
            }
            filter = EXTEfx.alGenFilters();
            EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
            return true;
        } catch (Throwable t) {
            disable(t);
            return false;
        }
    }

    private static void disable(Throwable t) {
        unavailable = true;
        OrbitalStrike.LOGGER.warn("Low-pass filter unavailable, falling back to volume attenuation", t);
    }

    /**
     * Sets attenuation level: 0 = normal audio, 1 = fully muffled.
     * Call each tick while active to cover newly started sounds.
     */
    public static void set(float amount) {
        if (unavailable) return;
        amount = Math.max(0f, Math.min(1f, amount));
        if (amount <= EPSILON && applied <= EPSILON) return;
        try {
            int target = EXTEfx.AL_FILTER_NULL;
            if (amount > EPSILON) {
                if (!ensureFilter()) return;
                EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, 1f + (MUFFLED_GAIN - 1f) * amount);
                EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, 1f + (MUFFLED_GAIN_HF - 1f) * amount);
                target = filter;
            }
            for (Iterator<Integer> it = SOURCES.iterator(); it.hasNext(); ) {
                int source = it.next();
                if (AL10.alIsSource(source)) {
                    int sourceFilter = EXEMPT.contains(source) ? EXTEfx.AL_FILTER_NULL : target;
                    AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, sourceFilter);
                } else {
                    it.remove();
                    EXEMPT.remove(source);
                }
            }
            applied = amount;
        } catch (Throwable t) {
            disable(t);
        }
    }
}
