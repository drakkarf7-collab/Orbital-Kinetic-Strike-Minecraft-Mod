package fr.orbitalstrike.cooldown;

import fr.orbitalstrike.registry.ModAttachments;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistent per-player cooldown (Fabric Data Attachment).
 * Stores the overworld tick when the cooldown expires: since it is saved
 * with the world, the cooldown survives disconnects and server restarts.
 */
@SuppressWarnings("UnstableApiUsage")
public final class CooldownStore {
    private CooldownStore() {}

    private static long now(ServerPlayerEntity player) {
        return player.getServer().getOverworld().getTime();
    }

    /** Remaining ticks (0 if ready). */
    public static long remaining(ServerPlayerEntity player, Identifier skill) {
        Map<String, Long> map = player.getAttachedOrElse(ModAttachments.COOLDOWNS, Map.of());
        Long end = map.get(skill.toString());
        if (end == null) return 0;
        return Math.max(0, end - now(player));
    }

    public static void start(ServerPlayerEntity player, Identifier skill, int ticks) {
        Map<String, Long> map = new HashMap<>(player.getAttachedOrElse(ModAttachments.COOLDOWNS, Map.of()));
        map.put(skill.toString(), now(player) + ticks);
        player.setAttached(ModAttachments.COOLDOWNS, map);
    }

    public static void reset(ServerPlayerEntity player) {
        player.removeAttached(ModAttachments.COOLDOWNS);
    }
}
