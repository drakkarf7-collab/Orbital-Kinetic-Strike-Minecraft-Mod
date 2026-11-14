package fr.orbitalstrike;

import fr.orbitalstrike.command.StrikeCommand;
import fr.orbitalstrike.config.ConfigManager;
import fr.orbitalstrike.crater.UndoStore;
import fr.orbitalstrike.network.ModNetworking;
import fr.orbitalstrike.registry.*;
import fr.orbitalstrike.skill.KineticStrikeSkill;
import fr.orbitalstrike.skill.SkillRegistry;
import fr.orbitalstrike.strike.StrikeManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint (client + server).
 * Registers content registries, networking, skills, and server events.
 */
public final class OrbitalStrike implements net.fabricmc.api.ModInitializer {
    public static final String MOD_ID = "orbital_strike";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ConfigManager.load();

        ModSounds.register();
        ModParticles.register();
        ModBlocks.register();
        ModItems.register();
        ModItemGroup.register();
        ModAttachments.register();

        SkillRegistry.register(new KineticStrikeSkill());

        ModNetworking.registerCommon();
        ModNetworking.registerServerReceivers();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            StrikeManager.tick(server);
            UndoStore.tick(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            StrikeManager.flushAll(server);
            UndoStore.flush();
        });

        // Invulnerability for the caster during the cinematic.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity p && StrikeManager.isInCinematic(p)));

        // Syncs cooldown and server limits on player join.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ModNetworking.syncCooldown(handler.getPlayer()));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                StrikeCommand.register(dispatcher));

        LOGGER.info("Orbital Kinetic Strike initialized.");
    }
}
