package fr.orbitalstrike.registry;

import fr.orbitalstrike.OrbitalStrike;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import org.jetbrains.annotations.Nullable;

/** Damage types (defined in JSON under data/orbital_strike/damage_type). */
public final class ModDamageTypes {
    public static final RegistryKey<DamageType> KINETIC_IMPACT =
            RegistryKey.of(RegistryKeys.DAMAGE_TYPE, OrbitalStrike.id("kinetic_impact"));
    public static final RegistryKey<DamageType> SHOCKWAVE =
            RegistryKey.of(RegistryKeys.DAMAGE_TYPE, OrbitalStrike.id("shockwave"));

    private ModDamageTypes() {}

    public static DamageSource source(ServerWorld world, RegistryKey<DamageType> key, @Nullable Entity attacker) {
        var entry = world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(key);
        return new DamageSource(entry, attacker);
    }
}
