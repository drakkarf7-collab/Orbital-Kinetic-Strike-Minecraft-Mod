package fr.orbitalstrike.client.targeting;

import fr.orbitalstrike.client.ClientConfig;
import fr.orbitalstrike.client.ClientState;
import fr.orbitalstrike.client.fx.FxController;
import fr.orbitalstrike.client.render.world.WorldFx;
import fr.orbitalstrike.crater.CraterProfile;
import fr.orbitalstrike.network.Payloads;
import fr.orbitalstrike.registry.ModItems;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Aiming and firing controller.
 * <ul>
 *   <li>Holding the Detonator: projects the future crater marker onto the aimed ground point,
 *       and a <b>right-click</b> launches the strike.</li>
 *   <li>Skill keybinding: hold to aim, release to strike, even if the Detonator is only in the inventory.</li>
 * </ul>
 */
public final class TargetingController {
    /** Minimum delay between two strike requests (anti-double-click), in ticks. */
    private static final int FIRE_DEBOUNCE_TICKS = 10;

    private static boolean aiming = false;
    private static boolean keyAiming = false;
    private static @Nullable BlockPos target;
    private static long lastFire = -FIRE_DEBOUNCE_TICKS;

    private TargetingController() {}

    public static boolean isAiming() {
        return aiming;
    }

    public static boolean hasDetonator(MinecraftClient client) {
        return client.player != null && client.player.getInventory().contains(ModItems.ORBITAL_DETONATOR.getDefaultStack());
    }

    public static boolean holdsDetonator(MinecraftClient client) {
        return client.player != null && (client.player.getMainHandStack().isOf(ModItems.ORBITAL_DETONATOR)
                || client.player.getOffHandStack().isOf(ModItems.ORBITAL_DETONATOR));
    }

    public static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            aiming = keyAiming = false;
            return;
        }
        ClientConfig cfg = ClientConfig.get();
        boolean free = client.currentScreen == null;
        boolean key = free && cfg.skillSelected && cfg.skillKey != InputUtil.UNKNOWN_KEY.getCode()
                && InputUtil.isKeyPressed(client.getWindow().getHandle(), cfg.skillKey);
        boolean held = free && holdsDetonator(client);

        if (keyAiming && !key) {
            // Keybinding released: strike the last aimed target.
            keyAiming = false;
            fire(client);
        }
        if (key && !hasDetonator(client)) {
            if (!keyAiming) client.player.sendMessage(Text.translatable("message.orbital_strike.no_item"), true);
            keyAiming = true;
            aiming = false;
            target = null;
            return;
        }
        // While own strike is active, stop aiming: the target is already locked.
        if (!(key || held) || FxController.hasOwnStrike()) {
            aiming = false;
            if (!key) target = null;
            return;
        }

        aiming = true;
        keyAiming = key;
        target = raycast(client);
        int remaining = ClientState.cooldownRemainingTicks();
        if (target != null) {
            int power = Math.min(cfg.power, ClientState.maxPower());
            WorldFx.aimMarker(Vec3d.ofBottomCenter(target).add(0, 1, 0), CraterProfile.basinRadius(power), remaining == 0);
        }
        if (remaining > 0) {
            client.player.sendMessage(Text.translatable("message.orbital_strike.cooldown", (remaining + 19) / 20), true);
        } else if (target != null) {
            client.player.sendMessage(Text.translatable(key ? "hud.orbital_strike.aiming_key" : "hud.orbital_strike.aiming",
                    target.toShortString()), true);
        } else {
            client.player.sendMessage(Text.translatable("message.orbital_strike.no_target"), true);
        }
    }

    /** Requests a strike on the aimed target. Returns true if the request was sent. */
    public static boolean fire(MinecraftClient client) {
        if (client.player == null || !hasDetonator(client)) return false;
        long now = ClientState.ticks();
        if (now - lastFire < FIRE_DEBOUNCE_TICKS || FxController.hasOwnStrike()) return false;
        BlockPos aim = target != null ? target : raycast(client);
        if (aim == null) {
            client.player.sendMessage(Text.translatable("message.orbital_strike.no_target"), true);
            return false;
        }
        int remaining = ClientState.cooldownRemainingTicks();
        if (remaining > 0) {
            client.player.sendMessage(Text.translatable("message.orbital_strike.cooldown", (remaining + 19) / 20), true);
            return false;
        }
        ClientConfig cfg = ClientConfig.get();
        int power = Math.min(cfg.power, ClientState.maxPower());
        ClientPlayNetworking.send(new Payloads.RequestStrike(aim, power, cfg.speed, cfg.mode().ordinal()));
        lastFire = now;
        target = null;
        return true;
    }

    private static @Nullable BlockPos raycast(MinecraftClient client) {
        HitResult hit = client.player.raycast(ClientState.maxRange(), 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        return ((BlockHitResult) hit).getBlockPos();
    }
}
