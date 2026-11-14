package fr.orbitalstrike.registry;

import fr.orbitalstrike.OrbitalStrike;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/** Particles: embers, ash, and rock debris. */
public final class ModParticles {
    public static final SimpleParticleType EMBER = reg("ember");
    public static final SimpleParticleType ASH = reg("ash");
    public static final SimpleParticleType DEBRIS = reg("debris");

    private ModParticles() {}

    private static SimpleParticleType reg(String name) {
        return Registry.register(Registries.PARTICLE_TYPE, OrbitalStrike.id(name), FabricParticleTypes.simple(true));
    }

    public static void register() {}
}
