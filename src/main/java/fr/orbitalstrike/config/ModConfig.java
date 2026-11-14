package fr.orbitalstrike.config;

/**
 * Server configuration (config/orbital_strike.json).
 * Public fields are serialized directly by Gson.
 */
public final class ModConfig {
    public int maxRange = 256;
    public int defaultPower = 3;
    public int maxPower = 5;
    public int cooldownSeconds = 300;
    public boolean terrainDestruction = true;
    public boolean affectPlayers = true;
    public float impactDamage = 1000.0f;
    public float shockwaveDamage = 30.0f;
    /** Maximum time spent per tick writing (or undoing) the crater, in milliseconds. */
    public int craterMsPerTick = 6;
    public int thermalConduitDecayMinutes = 10;
    /** Total cooling duration for molten rock. */
    public int moltenCoolingMinutes = 10;
    /** Server-wide shared cooldown (0 = disabled). */
    public int globalCooldownSeconds = 0;
    public int maxConcurrentStrikes = 3;
    /** Maximum number of debris blocks launched on impact (0 = none). */
    public int maxEjecta = 60;
    public boolean treeFelling = true;
    public int maxFelledTrees = 80;
    public boolean scorch = true;
    /** Rejects targets inside the protected spawn area. */
    public boolean respectSpawnProtection = true;
    /** Number of undoable strikes retained (0 = undo disabled). */
    public int undoMaxSnapshots = 3;
    public boolean strikeLog = true;
    public boolean showCinematicToNearbyPlayers = false;
    public boolean opOnly = false;

    /** Clamps out-of-range values after reading the config file. */
    public void sanitize() {
        maxRange = clamp(maxRange, 16, 1024);
        maxPower = clamp(maxPower, 1, 5);
        defaultPower = clamp(defaultPower, 1, maxPower);
        cooldownSeconds = clamp(cooldownSeconds, 0, 86400);
        impactDamage = Math.max(0f, impactDamage);
        shockwaveDamage = Math.max(0f, shockwaveDamage);
        craterMsPerTick = clamp(craterMsPerTick, 1, 40);
        moltenCoolingMinutes = clamp(moltenCoolingMinutes, 1, 1440);
        globalCooldownSeconds = clamp(globalCooldownSeconds, 0, 86400);
        maxConcurrentStrikes = clamp(maxConcurrentStrikes, 1, 64);
        maxEjecta = clamp(maxEjecta, 0, 400);
        maxFelledTrees = clamp(maxFelledTrees, 0, 1000);
        undoMaxSnapshots = clamp(undoMaxSnapshots, 0, 20);
        thermalConduitDecayMinutes = clamp(thermalConduitDecayMinutes, 1, 1440);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
