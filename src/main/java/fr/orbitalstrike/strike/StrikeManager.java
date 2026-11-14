package fr.orbitalstrike.strike;

import fr.orbitalstrike.skill.SkillContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages all active strikes. At most one active strike per player;
 * command-triggered strikes (without a player) are tracked separately.
 */
public final class StrikeManager {
    private static final Map<UUID, StrikeSession> BY_PLAYER = new HashMap<>();
    private static final List<StrikeSession> ANONYMOUS = new ArrayList<>();

    /** Overworld tick when the last strike started (global cooldown). */
    private static long lastStart = Long.MIN_VALUE / 2;

    private StrikeManager() {}

    public static void start(SkillContext ctx) {
        CinematicMode mode = ctx.player() == null ? CinematicMode.OFF : ctx.mode();
        StrikeTimeline timeline = StrikeTimeline.build(mode, ctx.speed());
        StrikeSession s = new StrikeSession(ctx.player(), ctx.world(), ctx.target(), ctx.power(), timeline, ctx.speed(), mode);
        lastStart = ctx.world().getServer().getOverworld().getTime();
        StrikeLog.record(s.strikeId, ctx.player() == null ? "console" : ctx.player().getGameProfile().getName(),
                ctx.world().getRegistryKey().getValue().toString(), ctx.target(), ctx.power());
        if (ctx.player() != null) {
            BY_PLAYER.put(ctx.player().getUuid(), s);
        } else {
            ANONYMOUS.add(s);
        }
    }

    public static boolean hasActive(ServerPlayerEntity player) {
        return BY_PLAYER.containsKey(player.getUuid());
    }

    public static boolean isInCinematic(ServerPlayerEntity player) {
        StrikeSession s = BY_PLAYER.get(player.getUuid());
        return s != null && s.inCinematic();
    }

    /** Remaining server-wide global cooldown ticks (0 if ready). */
    public static long globalCooldownRemaining(MinecraftServer server) {
        long total = fr.orbitalstrike.config.ConfigManager.get().globalCooldownSeconds * 20L;
        if (total <= 0) return 0;
        long elapsed = server.getOverworld().getTime() - lastStart;
        return elapsed < 0 ? 0 : Math.max(0, total - elapsed);
    }

    public static int activeCount() {
        return BY_PLAYER.size() + ANONYMOUS.size();
    }

    public static void tick(MinecraftServer server) {
        if (BY_PLAYER.isEmpty() && ANONYMOUS.isEmpty()) return;
        for (Iterator<StrikeSession> it = BY_PLAYER.values().iterator(); it.hasNext(); ) {
            if (!it.next().tick()) it.remove();
        }
        ANONYMOUS.removeIf(s -> !s.tick());
    }

    public static void flushAll(MinecraftServer server) {
        BY_PLAYER.values().forEach(StrikeSession::flush);
        ANONYMOUS.forEach(StrikeSession::flush);
        BY_PLAYER.clear();
        ANONYMOUS.clear();
        lastStart = Long.MIN_VALUE / 2;
    }
}
