package fr.orbitalstrike.network;

import fr.orbitalstrike.config.ConfigManager;
import fr.orbitalstrike.cooldown.CooldownStore;
import fr.orbitalstrike.skill.KineticStrikeSkill;
import fr.orbitalstrike.skill.Skill;
import fr.orbitalstrike.skill.SkillContext;
import fr.orbitalstrike.skill.SkillRegistry;
import fr.orbitalstrike.strike.CinematicMode;
import fr.orbitalstrike.strike.StrikeTimeline;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

/** Packet registration and server-side receivers. */
public final class ModNetworking {
    private ModNetworking() {}

    public static void registerCommon() {
        PayloadTypeRegistry.playC2S().register(Payloads.RequestStrike.ID, Payloads.RequestStrike.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Phase.ID, Payloads.Phase.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Impact.ID, Payloads.Impact.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.SkyStreak.ID, Payloads.SkyStreak.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Shockwave.ID, Payloads.Shockwave.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.CooldownSync.ID, Payloads.CooldownSync.CODEC);
    }

    public static void registerServerReceivers() {
        // Fabric 1.21 receivers run on the server thread.
        ServerPlayNetworking.registerGlobalReceiver(Payloads.RequestStrike.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            Skill skill = SkillRegistry.get(KineticStrikeSkill.ID);
            if (skill == null) return;
            var cfg = ConfigManager.get();
            int power = Math.max(1, Math.min(cfg.maxPower, payload.power()));
            SkillContext ctx = new SkillContext(player, player.getServerWorld(), payload.target(), power,
                    StrikeTimeline.clampSpeed(payload.speed()), CinematicMode.byId(payload.mode()));
            var result = skill.validate(ctx);
            if (!result.ok()) {
                player.sendMessage(result.reason(), true);
                syncCooldown(player);
                return;
            }
            skill.execute(ctx);
            syncCooldown(player);
        });
    }

    public static void syncCooldown(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, Payloads.CooldownSync.ID)) return;
        var cfg = ConfigManager.get();
        int remaining = (int) CooldownStore.remaining(player, KineticStrikeSkill.ID);
        ServerPlayNetworking.send(player, new Payloads.CooldownSync(remaining, cfg.cooldownSeconds * 20,
                cfg.maxRange, cfg.maxPower));
    }
}
