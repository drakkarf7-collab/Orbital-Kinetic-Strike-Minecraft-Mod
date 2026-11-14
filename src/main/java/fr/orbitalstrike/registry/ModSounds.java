package fr.orbitalstrike.registry;

import fr.orbitalstrike.OrbitalStrike;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * Custom sound events for Orbital Kinetic Strike.
 */
public final class ModSounds {

    public static final SoundEvent MARK = register("mark");
    public static final SoundEvent COUNTDOWN = register("countdown");
    public static final SoundEvent ASCENT = register("ascent");
    public static final SoundEvent TRANSFER = register("transfer");
    public static final SoundEvent RING = register("ring");
    public static final SoundEvent LOAD = register("load");
    public static final SoundEvent SPIN = register("spin");
    public static final SoundEvent RELEASE = register("release");
    public static final SoundEvent FLIGHT = register("flight");
    public static final SoundEvent STREAK = register("streak");
    public static final SoundEvent HUSH = register("hush");
    public static final SoundEvent CRACK = register("crack");
    public static final SoundEvent BOOM = register("boom");
    public static final SoundEvent WIND = register("wind");
    public static final SoundEvent HUM = register("hum");

    private ModSounds() {}

    private static SoundEvent register(String name) {
        Identifier id = OrbitalStrike.id(name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    public static void register() {}
}
