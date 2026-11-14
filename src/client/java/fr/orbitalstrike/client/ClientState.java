package fr.orbitalstrike.client;

import fr.orbitalstrike.network.Payloads;

/**
 * Shared client state: local tick clock (paused when the game is paused),
 * cooldown synced from the server, and server limits.
 */
public final class ClientState {
    private static long ticks = 0;
    private static long cooldownEnd = 0;
    private static int cooldownTotal = 300 * 20;
    private static int maxRange = 256;
    private static int maxPower = 5;

    private ClientState() {}

    public static void tick() {
        ticks++;
    }

    public static long ticks() {
        return ticks;
    }

    public static void onSync(Payloads.CooldownSync p) {
        cooldownEnd = ticks + p.remainingTicks();
        cooldownTotal = p.totalTicks();
        maxRange = p.maxRange();
        maxPower = p.maxPower();
    }

    public static int cooldownRemainingTicks() {
        return (int) Math.max(0, cooldownEnd - ticks);
    }

    public static int cooldownTotalTicks() {
        return cooldownTotal;
    }

    public static int maxRange() {
        return maxRange;
    }

    public static int maxPower() {
        return maxPower;
    }

    public static void reset() {
        cooldownEnd = 0;
    }
}
