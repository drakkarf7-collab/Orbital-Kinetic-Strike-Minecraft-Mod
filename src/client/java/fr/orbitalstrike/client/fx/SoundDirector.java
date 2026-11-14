package fr.orbitalstrike.client.fx;

import fr.orbitalstrike.client.ClientState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Manages pre-impact audio muffling (hush) and distance-delayed sounds
 * (impact audio arrives at the speed of sound).
 */
public final class SoundDirector {
    /** Minimum volume floor: at 0, Minecraft permanently stops active sounds. */
    private static final float MIN_VOLUME = 0.03f;
    /** Speed of sound in blocks per tick (343 m/s / 20). */
    public static final double SOUND_SPEED_PER_TICK = 343.0 / 20.0;

    private static float target = 1f;
    private static float current = 1f;
    private static float fadeStep = 0.05f;

    private record Scheduled(long at, SoundEvent sound, float volume, float pitch, Runnable then) {}

    private static final List<Scheduled> SCHEDULED = new ArrayList<>();

    private SoundDirector() {}

    /** With the low-pass filter active, keep audible volume: muffling comes from the filter rather than level reduction. */
    private static final float FILTERED_FLOOR = 0.45f;

    /** Current muffling amount: 0 = normal world audio, 1 = full silence. */
    public static float silenceAmount() {
        return (1f - current) / (1f - MIN_VOLUME);
    }

    public static float worldVolumeMultiplier() {
        if (current < 0.999f && LowPassFilter.available()) return 1f + (FILTERED_FLOOR - 1f) * silenceAmount();
        return current;
    }

    /** Gradually muffles world audio over {@code ticks} ticks. */
    public static void beginSilence(int ticks) {
        target = MIN_VOLUME;
        fadeStep = (1f - MIN_VOLUME) / Math.max(1, ticks * 0.6f);
    }

    /** Restores normal volume over a few ticks. */
    public static void endSilence() {
        target = 1f;
        fadeStep = 0.15f;
    }

    public static boolean isSilenced() {
        return current < 0.999f || target < 1f;
    }

    public static void play(SoundEvent sound, float volume, float pitch) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(sound, pitch, volume));
    }

    /** Cutscene skipped (Escape): stops active mod cutscene sounds so the rest of the shot does not play out. */
    public static void stopCinematicSounds() {
        var manager = MinecraftClient.getInstance().getSoundManager();
        for (SoundEvent sound : new SoundEvent[]{fr.orbitalstrike.registry.ModSounds.ASCENT, fr.orbitalstrike.registry.ModSounds.TRANSFER,
                fr.orbitalstrike.registry.ModSounds.RING, fr.orbitalstrike.registry.ModSounds.LOAD,
                fr.orbitalstrike.registry.ModSounds.SPIN, fr.orbitalstrike.registry.ModSounds.RELEASE,
                fr.orbitalstrike.registry.ModSounds.FLIGHT}) {
            manager.stopSounds(sound.getId(), SoundCategory.MASTER);
        }
    }

    public static void playAt(SoundEvent sound, double x, double y, double z, float volume, float pitch) {
        var client = MinecraftClient.getInstance();
        if (client.world == null) return;
        client.getSoundManager().play(new PositionedSoundInstance(sound, SoundCategory.MASTER, volume, pitch,
                client.world.getRandom(), x, y, z));
    }

    public static void schedule(int delayTicks, SoundEvent sound, float volume, float pitch, Runnable then) {
        SCHEDULED.add(new Scheduled(ClientState.ticks() + delayTicks, sound, volume, pitch, then));
    }

    /** Sound arrival delay by distance, minimum 6 ticks (0.3 s). */
    public static int delayForDistance(double distance) {
        return (int) Math.max(6, Math.round(distance / SOUND_SPEED_PER_TICK));
    }

    public static void tick() {
        float before = current;
        if (current > target) current = Math.max(target, current - fadeStep);
        else if (current < target) current = Math.min(target, current + fadeStep);
        if (before != current) refreshVolumes();
        // True low-pass (OpenAL EFX): reapplied every tick to cover newly started sounds.
        LowPassFilter.set(silenceAmount());

        long now = ClientState.ticks();
        for (Iterator<Scheduled> it = SCHEDULED.iterator(); it.hasNext(); ) {
            Scheduled s = it.next();
            if (s.at <= now) {
                it.remove();
                if (s.then != null) s.then.run();
                play(s.sound, s.volume, s.pitch);
            }
        }
    }

    /** Forces Minecraft to recompute the volume of all active sounds. */
    private static void refreshVolumes() {
        var client = MinecraftClient.getInstance();
        client.getSoundManager().updateSoundVolume(SoundCategory.AMBIENT,
                client.options.getSoundVolume(SoundCategory.AMBIENT));
    }

    public static void reset() {
        SCHEDULED.clear();
        target = 1f;
        if (current != 1f) {
            current = 1f;
            refreshVolumes();
            LowPassFilter.set(0f);
        }
    }
}
