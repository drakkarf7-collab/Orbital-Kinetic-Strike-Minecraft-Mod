package fr.orbitalstrike.strike;

import fr.orbitalstrike.config.ConfigManager;
import fr.orbitalstrike.config.ModConfig;
import fr.orbitalstrike.crater.ImpactCraterGenerator;
import fr.orbitalstrike.crater.UndoSnapshot;
import fr.orbitalstrike.crater.UndoStore;
import fr.orbitalstrike.network.Payloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side execution of an orbital strike:
 * steps through {@link StrikeTimeline} phases, broadcasts phase and effect packets
 * ({@link Payloads.Phase}, {@link Payloads.SkyStreak}, {@link Payloads.Impact}, {@link Payloads.Shockwave})
 * to nearby players, then carves the crater and syncs chunks within a per-tick budget.
 */
public final class StrikeSession {
    private static final double MIN_NOTIFY_RANGE = 512.0;
    private static final int CHUNK_SYNC_PER_TICK = 36;
    private static final double STREAK_ENTRY_HEIGHT = 480.0;

    final java.util.UUID strikeId;
    final @Nullable java.util.UUID owner;
    final ServerWorld world;
    final BlockPos target;
    final int power;
    final StrikeTimeline timeline;
    final float speed;
    final long seed;
    final ModConfig cfg = ConfigManager.get();

    private int entryIndex = 0;
    private int phaseElapsed = 0;
    private boolean phaseEntered = false;
    private boolean done = false;
    private int shockwaveDuration = 1;
    private double shockwaveElapsed = 0.0;

    private @Nullable ImpactCraterGenerator crater;
    private @Nullable ImpactDamage damage;
    private @Nullable Vec3d anchor;
    private boolean ownerCinematic;
    private StrikePhase phase = StrikePhase.MARK;

    StrikeSession(@Nullable ServerPlayerEntity owner, ServerWorld world, BlockPos target, int power,
                  StrikeTimeline timeline) {
        this(owner, world, target, power, timeline, 1.0f, CinematicMode.FULL);
    }

    StrikeSession(@Nullable ServerPlayerEntity owner, ServerWorld world, BlockPos target, int power,
                  StrikeTimeline timeline, float speed, CinematicMode mode) {
        this.strikeId = java.util.UUID.randomUUID();
        this.owner = owner == null ? null : owner.getUuid();
        this.world = world;
        this.target = target;
        this.power = power;
        this.timeline = timeline;
        this.speed = StrikeTimeline.clampSpeed(speed);
        this.seed = world.getRandom().nextLong();
        this.ownerCinematic = owner != null && mode != CinematicMode.OFF;
    }

    public StrikePhase phase() {
        return phase;
    }

    boolean inCinematic() {
        return ownerCinematic && !done && (phase.cinematic || phase == StrikePhase.MARK
                || phase == StrikePhase.COUNTDOWN || phase == StrikePhase.STREAK || phase == StrikePhase.HUSH);
    }

    /** Advances the strike sequence by one server tick. Returns false once everything is complete. */
    boolean tick() {
        ServerPlayerEntity ownerPlayer = ownerPlayer();
        if (ownerCinematic && ownerPlayer == null) {
            ownerCinematic = false;
        }
        if (inCinematic() && ownerPlayer != null) {
            holdOwner(ownerPlayer);
        }

        if (!done) {
            advanceTimeline();
        }
        stepCrater();

        return !done || (crater != null && (!crater.isDone() || crater.hasPendingSync()));
    }

    private void advanceTimeline() {
        if (entryIndex >= timeline.size()) {
            phase = StrikePhase.DONE;
            done = true;
            return;
        }

        StrikeTimeline.Entry entry = timeline.get(entryIndex);
        if (!phaseEntered) {
            enterPhase(entry);
            phaseEntered = true;
            phaseElapsed = 0;
        }

        phaseElapsed++;
        if (phase == StrikePhase.SHOCKWAVE) {
            shockwaveElapsed = phaseElapsed;
            if (damage != null && (phaseElapsed % 2 == 0 || phaseElapsed >= entry.ticks())) {
                double scale = powerScale();
                double waveR = ImpactCraterGenerator.shockwaveRadius(shockwaveElapsed, shockwaveDuration, 400.0 * scale);
                damage.waveAtRadius(waveR);
            }
        }

        boolean finishedPhase;
        if (entry.phase() == StrikePhase.SETTLE || entry.ticks() <= 0) {
            finishedPhase = crater == null || (crater.isDone() && !crater.hasPendingSync());
        } else {
            finishedPhase = phaseElapsed >= entry.ticks();
        }

        if (finishedPhase) {
            entryIndex++;
            phaseEntered = false;
            if (entryIndex >= timeline.size()) {
                phase = StrikePhase.DONE;
                done = true;
            }
        }
    }

    private void enterPhase(StrikeTimeline.Entry entry) {
        phase = entry.phase();
        broadcastPhase(phase, entry.ticks());

        double scale = powerScale();
        Vec3d c = impactCenter();

        switch (phase) {
            case STREAK -> {
                // Purely vertical drop straight down from the zenith above the impact point.
                Vec3d from = c.add(0.0, STREAK_ENTRY_HEIGHT, 0.0);
                broadcast(new Payloads.SkyStreak(
                        strikeId, from.x, from.y, from.z, c.x, c.y, c.z, Math.max(1, entry.ticks())
                ));
            }
            case IMPACT -> doImpact();
            case SHOCKWAVE -> {
                shockwaveDuration = Math.max(1, entry.ticks());
                shockwaveElapsed = 0.0;
                broadcast(new Payloads.Shockwave(
                        strikeId, c.x, c.y, c.z,
                        (float) (20.0 * scale), (float) (400.0 * scale), shockwaveDuration
                ));
            }
            default -> {}
        }
    }

    private void stepCrater() {
        if (crater == null) return;
        if (!crater.isDone()) {
            double scale = powerScale();
            double radiusLimit;
            if (phase == StrikePhase.SHOCKWAVE) {
                double waveR = ImpactCraterGenerator.shockwaveRadius(
                        Math.max(1.0, shockwaveElapsed), shockwaveDuration, crater.radius());
                radiusLimit = Math.max(0.0, Math.min(waveR + 24.0 * scale, crater.radius() + 24.0));
            } else {
                radiusLimit = crater.radius() + 32.0;
            }
            long budgetNanos = Math.max(cfg.craterMsPerTick * 3L, 22L) * 1_000_000L;
            crater.tickUpTo(radiusLimit, budgetNanos);
            if (crater.isDone()) {
                storeUndo();
            }
        }
        crater.flushChunkSync(CHUNK_SYNC_PER_TICK);
    }

    void flush() {
        if (crater != null && !crater.isDone()) {
            crater.finishNow();
            storeUndo();
        }
    }

    private void storeUndo() {
        if (crater != null && crater.snapshot() != null) {
            UndoStore.add(world.getServer(), crater.snapshot());
        }
    }

    private void doImpact() {
        double scale = powerScale();
        double coreRadius = 160.0 * scale;
        double visualBasinRadius = 88.0 * scale;
        Vec3d c = impactCenter();

        damage = new ImpactDamage(world, c, coreRadius, ownerPlayer(), cfg);
        damage.core();

        broadcast(new Payloads.Impact(strikeId, c.x, c.y, c.z, (float) visualBasinRadius, power));

        if (cfg.terrainDestruction) {
            UndoSnapshot snapshot = cfg.undoMaxSnapshots > 0
                    ? new UndoSnapshot(strikeId, world.getRegistryKey().getValue(), target, power, System.currentTimeMillis())
                    : null;
            crater = new ImpactCraterGenerator(world, target, power, seed, cfg, snapshot);
            crater.impactBurst();
        }
        if (cfg.maxEjecta > 0) {
            Ejecta.spawn(world, target, power, cfg.maxEjecta, seed);
        }
    }

    private void broadcastPhase(StrikePhase p, int durationTicks) {
        double range = notifyRange();
        Vec3d c = impactCenter();
        for (ServerPlayerEntity pl : world.getPlayers()) {
            boolean isOwner = pl.getUuid().equals(owner);
            if (!isOwner && pl.squaredDistanceTo(c) > range * range) continue;
            if (!ServerPlayNetworking.canSend(pl, Payloads.Phase.ID)) continue;
            boolean cinematic = isOwner ? ownerCinematic : (ownerCinematic && cfg.showCinematicToNearbyPlayers);
            ServerPlayNetworking.send(pl, new Payloads.Phase(
                    strikeId, p.ordinal(), durationTicks, target, power, cinematic, isOwner
            ));
        }
    }

    private void broadcast(CustomPayload payload) {
        double range = notifyRange();
        Vec3d c = impactCenter();
        for (ServerPlayerEntity pl : world.getPlayers()) {
            boolean isOwner = pl.getUuid().equals(owner);
            if (!isOwner && pl.squaredDistanceTo(c) > range * range) continue;
            if (!ServerPlayNetworking.canSend(pl, payload.getId())) continue;
            ServerPlayNetworking.send(pl, payload);
        }
    }

    private double powerScale() {
        return Math.max(0.25, power / 4.0);
    }

    private double notifyRange() {
        return Math.max(MIN_NOTIFY_RANGE, 400.0 * powerScale() * 2.5 + 200.0);
    }

    private void holdOwner(ServerPlayerEntity p) {
        if (anchor == null) anchor = p.getPos();
        p.setVelocity(Vec3d.ZERO);
        p.fallDistance = 0;
        if (p.getPos().squaredDistanceTo(anchor) > 0.01) {
            p.networkHandler.requestTeleport(anchor.x, anchor.y, anchor.z, p.getYaw(), p.getPitch());
        }
    }

    private Vec3d impactCenter() {
        return new Vec3d(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
    }

    private @Nullable ServerPlayerEntity ownerPlayer() {
        if (owner == null) return null;
        var p = world.getServer().getPlayerManager().getPlayer(owner);
        return p != null && p.getServerWorld() == world ? p : null;
    }
}
