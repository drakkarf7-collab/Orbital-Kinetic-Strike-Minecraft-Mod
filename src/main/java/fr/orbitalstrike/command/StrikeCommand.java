package fr.orbitalstrike.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.orbitalstrike.config.ConfigManager;
import fr.orbitalstrike.cooldown.CooldownStore;
import fr.orbitalstrike.crater.UndoSnapshot;
import fr.orbitalstrike.crater.UndoStore;
import fr.orbitalstrike.network.ModNetworking;
import fr.orbitalstrike.skill.SkillContext;
import fr.orbitalstrike.strike.CinematicMode;
import fr.orbitalstrike.strike.StrikeLog;
import fr.orbitalstrike.strike.StrikeManager;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Operator commands (permission level 2):
 * <pre>
 * /orbitalstrike strike &lt;pos&gt; [power]   immediate strike without cinematic
 * /orbitalstrike resetcooldown [player]
 * /orbitalstrike undo [id]               restores terrain prior to the latest (or matching) strike
 * /orbitalstrike log                     lists recent strikes
 * /orbitalstrike reload
 * </pre>
 */
public final class StrikeCommand {
    private StrikeCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("orbitalstrike")
                .requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.literal("strike")
                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                .executes(ctx -> strike(ctx, ConfigManager.get().defaultPower))
                                .then(CommandManager.argument("power", IntegerArgumentType.integer(1, 5))
                                        .executes(ctx -> strike(ctx, IntegerArgumentType.getInteger(ctx, "power"))))))
                .then(CommandManager.literal("resetcooldown")
                        .executes(ctx -> reset(ctx, ctx.getSource().getPlayerOrThrow()))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(ctx -> reset(ctx, EntityArgumentType.getPlayer(ctx, "player")))))
                .then(CommandManager.literal("undo")
                        .executes(ctx -> undo(ctx, null))
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .executes(ctx -> undo(ctx, StringArgumentType.getString(ctx, "id")))))
                .then(CommandManager.literal("log").executes(StrikeCommand::log))
                .then(CommandManager.literal("reload")
                        .executes(ctx -> {
                            ConfigManager.load();
                            ctx.getSource().sendFeedback(() -> Text.translatable("command.orbital_strike.reloaded"), true);
                            return 1;
                        })));
    }

    private static int strike(CommandContext<ServerCommandSource> ctx, int power) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        BlockPos pos = BlockPosArgumentType.getLoadedBlockPos(ctx, "pos");
        StrikeManager.start(new SkillContext(null, src.getWorld(), pos, power, 1.0f, CinematicMode.OFF));
        src.sendFeedback(() -> Text.translatable("command.orbital_strike.strike", pos.toShortString(), power), true);
        return 1;
    }

    private static int undo(CommandContext<ServerCommandSource> ctx, String idPrefix) {
        ServerCommandSource src = ctx.getSource();
        UndoSnapshot snapshot = UndoStore.find(src.getWorld(), idPrefix);
        if (snapshot == null) {
            src.sendError(Text.translatable("command.orbital_strike.undo.none"));
            return 0;
        }
        if (!snapshot.dimension().equals(src.getWorld().getRegistryKey().getValue())) {
            src.sendError(Text.translatable("command.orbital_strike.undo.wrong_dimension", snapshot.dimension().toString()));
            return 0;
        }
        if (StrikeManager.activeCount() > 0) {
            src.sendError(Text.translatable("command.orbital_strike.undo.busy"));
            return 0;
        }
        int blocks = UndoStore.begin(src.getWorld(), snapshot);
        String at = snapshot.center().toShortString();
        src.sendFeedback(() -> Text.translatable("command.orbital_strike.undo.started", blocks, at), true);
        return blocks;
    }

    private static int log(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        var entries = StrikeLog.recent();
        if (entries.isEmpty()) {
            src.sendFeedback(() -> Text.translatable("command.orbital_strike.log.empty"), false);
            return 0;
        }
        for (StrikeLog.Entry e : entries) src.sendFeedback(() -> Text.literal(e.line()), false);
        return entries.size();
    }

    private static int reset(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity player) {
        CooldownStore.reset(player);
        ModNetworking.syncCooldown(player);
        ctx.getSource().sendFeedback(() -> Text.translatable("command.orbital_strike.reset", player.getDisplayName()), true);
        return 1;
    }
}
