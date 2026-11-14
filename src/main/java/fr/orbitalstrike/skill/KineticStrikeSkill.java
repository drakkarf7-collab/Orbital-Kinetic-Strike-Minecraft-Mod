package fr.orbitalstrike.skill;

import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.config.ConfigManager;
import fr.orbitalstrike.config.ModConfig;
import fr.orbitalstrike.cooldown.CooldownStore;
import fr.orbitalstrike.registry.ModItems;
import fr.orbitalstrike.strike.StrikeManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/** Kinetic Strike: tungsten rod launched from orbit. */
public final class KineticStrikeSkill implements Skill {
    public static final Identifier ID = OrbitalStrike.id("kinetic_strike");

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public int cooldownTicks(ModConfig cfg) {
        return cfg.cooldownSeconds * 20;
    }

    @Override
    public ValidationResult validate(SkillContext ctx) {
        ServerPlayerEntity player = ctx.player();
        if (player == null) return ValidationResult.OK;
        ModConfig cfg = ConfigManager.get();

        if (cfg.opOnly && !player.hasPermissionLevel(2)) {
            return ValidationResult.fail("message.orbital_strike.no_permission");
        }
        if (!player.getInventory().contains(ModItems.ORBITAL_DETONATOR.getDefaultStack())) {
            return ValidationResult.fail("message.orbital_strike.no_item");
        }
        if (StrikeManager.hasActive(player)) {
            return ValidationResult.fail("message.orbital_strike.already_active");
        }
        if (StrikeManager.activeCount() >= cfg.maxConcurrentStrikes) {
            return ValidationResult.fail("message.orbital_strike.too_many");
        }
        long global = StrikeManager.globalCooldownRemaining(ctx.world().getServer());
        if (global > 0 && !player.hasPermissionLevel(2)) {
            return ValidationResult.fail("message.orbital_strike.global_cooldown", (global + 19) / 20);
        }
        long remaining = CooldownStore.remaining(player, ID);
        if (remaining > 0) {
            return ValidationResult.fail("message.orbital_strike.cooldown", (remaining + 19) / 20);
        }
        if (player.getServerWorld() != ctx.world() || !ctx.world().isInBuildLimit(ctx.target())) {
            return ValidationResult.fail("message.orbital_strike.invalid_target");
        }
        // Small margin for network latency and client raycast rounding.
        Vec3d eyes = player.getEyePos();
        if (eyes.distanceTo(Vec3d.ofCenter(ctx.target())) > cfg.maxRange + 4) {
            return ValidationResult.fail("message.orbital_strike.too_far", cfg.maxRange);
        }
        if (!ctx.world().isChunkLoaded(ctx.target().getX() >> 4, ctx.target().getZ() >> 4)) {
            return ValidationResult.fail("message.orbital_strike.invalid_target");
        }
        if (cfg.respectSpawnProtection && ctx.world().getServer().isSpawnProtected(ctx.world(), ctx.target(), player)) {
            return ValidationResult.fail("message.orbital_strike.protected");
        }
        return ValidationResult.OK;
    }

    @Override
    public void execute(SkillContext ctx) {
        if (ctx.player() != null) {
            CooldownStore.start(ctx.player(), ID, cooldownTicks(ConfigManager.get()));
        }
        StrikeManager.start(ctx);
    }
}
