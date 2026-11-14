package fr.orbitalstrike.registry;

import com.mojang.serialization.Codec;
import fr.orbitalstrike.OrbitalStrike;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import java.util.Map;

/**
 * Persistent data attached to players:
 * skill ID -> overworld tick when cooldown expires.
 */
@SuppressWarnings("UnstableApiUsage")
public final class ModAttachments {
    public static final AttachmentType<Map<String, Long>> COOLDOWNS = AttachmentRegistry.<Map<String, Long>>builder()
            .persistent(Codec.unboundedMap(Codec.STRING, Codec.LONG))
            .copyOnDeath()
            .initializer(Map::of)
            .buildAndRegister(OrbitalStrike.id("cooldowns"));

    private ModAttachments() {}

    public static void register() {}
}
