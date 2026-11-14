package fr.orbitalstrike.strike;

import fr.orbitalstrike.config.ModConfig;
import fr.orbitalstrike.registry.ModDamageTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Strike damage: core impact at the center, followed by an expanding shockwave.
 * A UUID set ensures each entity is hit at most once by the shockwave.
 */
public final class ImpactDamage {
    private final ServerWorld world;
    private final Vec3d center;
    private final double radius;
    private final @Nullable Entity attacker;
    private final ModConfig cfg;
    private final Set<UUID> waveHit = new HashSet<>();
    private double lastWaveRadius;

    public ImpactDamage(ServerWorld world, Vec3d center, double radius, @Nullable Entity attacker, ModConfig cfg) {
        this.world = world;
        this.center = center;
        this.radius = radius;
        this.attacker = attacker;
        this.cfg = cfg;
        this.lastWaveRadius = radius;
    }

    public double waveEndRadius() {
        return radius * 2.5;
    }

    /** Impact core: massive damage and destruction of dropped items. */
    public void core() {
        Box box = new Box(center, center).expand(radius, radius + 64, radius);
        for (Entity e : world.getOtherEntities(null, box)) {
            if (horizontalDistance(e) > radius) continue;
            if (e instanceof ItemEntity) {
                e.discard();
                continue;
            }
            if (!canAffect(e)) continue;
            e.damage(ModDamageTypes.source(world, ModDamageTypes.KINETIC_IMPACT, attacker), cfg.impactDamage);
            waveHit.add(e.getUuid());
        }
    }

    /** Progressive shockwave front damage up to {@code waveRadius}. */
    public void waveAtRadius(double waveRadius) {
        if (waveRadius <= radius) return;
        double progress = (waveRadius - radius) / Math.max(1.0, waveEndRadius() - radius);
        wave(Math.min(1.0, progress));
    }

    /** Shockwave sweep: progress from 0 to 1 over the phase duration. */
    public void wave(double progress) {
        double current = radius + Math.min(1.0, progress) * (waveEndRadius() - radius);
        if (current <= lastWaveRadius) return;
        Box box = new Box(center, center).expand(current, 48, current);
        for (Entity e : world.getOtherEntities(null, box)) {
            if (!(e instanceof LivingEntity living) || !canAffect(e)) continue;
            double d = horizontalDistance(e);
            if (d > current || d < lastWaveRadius - 1 || !waveHit.add(e.getUuid())) continue;

            double falloff = 1.0 - (d - radius) / (waveEndRadius() - radius);
            float dmg = (float) (cfg.shockwaveDamage * Math.max(0.15, falloff));
            e.damage(ModDamageTypes.source(world, ModDamageTypes.SHOCKWAVE, attacker), dmg);

            Vec3d dir = new Vec3d(e.getX() - center.x, 0, e.getZ() - center.z);
            dir = dir.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : dir.normalize();
            double push = 1.2 + 2.0 * Math.max(0, falloff);
            e.addVelocity(dir.x * push, 0.5 + 0.4 * falloff, dir.z * push);
            e.velocityModified = true;
            living.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 1));
            living.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 100, 0));
        }
        lastWaveRadius = current;
    }

    private boolean canAffect(Entity e) {
        if (e instanceof PlayerEntity p) {
            if (!cfg.affectPlayers && p != attacker) return false;
            return !p.isSpectator();
        }
        return true;
    }

    private double horizontalDistance(Entity e) {
        double dx = e.getX() - center.x, dz = e.getZ() - center.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
