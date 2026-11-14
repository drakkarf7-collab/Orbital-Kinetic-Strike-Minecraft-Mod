package fr.orbitalstrike.network;

import fr.orbitalstrike.OrbitalStrike;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * All mod network payloads.
 * Codecs are hand-written (PacketCodec.of) to remain simple and explicit.
 */
public final class Payloads {
    private Payloads() {}

    private static <T extends CustomPayload> CustomPayload.Id<T> id(String name) {
        return new CustomPayload.Id<>(OrbitalStrike.id(name));
    }

    // ---------------------------------------------------------------- C2S

    /** Player requests a strike on {@code target}. All inputs are revalidated server-side. */
    public record RequestStrike(BlockPos target, int power, float speed, int mode) implements CustomPayload {
        public static final Id<RequestStrike> ID = id("request_strike");
        public static final PacketCodec<RegistryByteBuf, RequestStrike> CODEC = PacketCodec.of(
                (v, buf) -> {
                    buf.writeBlockPos(v.target);
                    buf.writeVarInt(v.power);
                    buf.writeFloat(v.speed);
                    buf.writeVarInt(v.mode);
                },
                buf -> new RequestStrike(buf.readBlockPos(), buf.readVarInt(), buf.readFloat(), buf.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ---------------------------------------------------------------- S2C

    /** Start of a strike phase. {@code cinematic}: whether this recipient should play the cinematic. */
    public record Phase(UUID strikeId, int phase, int durationTicks, BlockPos target, int power,
                        boolean cinematic, boolean owner) implements CustomPayload {
        public static final Id<Phase> ID = id("phase");
        public static final PacketCodec<RegistryByteBuf, Phase> CODEC = PacketCodec.of(
                (v, buf) -> {
                    buf.writeUuid(v.strikeId);
                    buf.writeVarInt(v.phase);
                    buf.writeVarInt(v.durationTicks);
                    buf.writeBlockPos(v.target);
                    buf.writeVarInt(v.power);
                    buf.writeBoolean(v.cinematic);
                    buf.writeBoolean(v.owner);
                },
                buf -> new Phase(buf.readUuid(), buf.readVarInt(), buf.readVarInt(), buf.readBlockPos(),
                        buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** The kinetic rod has just hit the ground. */
    public record Impact(UUID strikeId, double x, double y, double z, float radius, int power) implements CustomPayload {
        public static final Id<Impact> ID = id("impact");
        public static final PacketCodec<RegistryByteBuf, Impact> CODEC = PacketCodec.of(
                (v, buf) -> {
                    buf.writeUuid(v.strikeId);
                    buf.writeDouble(v.x);
                    buf.writeDouble(v.y);
                    buf.writeDouble(v.z);
                    buf.writeFloat(v.radius);
                    buf.writeVarInt(v.power);
                },
                buf -> new Impact(buf.readUuid(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readFloat(), buf.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** The rod streaks across the sky from {@code from} to {@code to}, visible to all players. */
    public record SkyStreak(UUID strikeId, double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
                            int durationTicks) implements CustomPayload {
        public static final Id<SkyStreak> ID = id("sky_streak");
        public static final PacketCodec<RegistryByteBuf, SkyStreak> CODEC = PacketCodec.of(
                (v, buf) -> {
                    buf.writeUuid(v.strikeId);
                    buf.writeDouble(v.fromX);
                    buf.writeDouble(v.fromY);
                    buf.writeDouble(v.fromZ);
                    buf.writeDouble(v.toX);
                    buf.writeDouble(v.toY);
                    buf.writeDouble(v.toZ);
                    buf.writeVarInt(v.durationTicks);
                },
                buf -> new SkyStreak(buf.readUuid(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Start of the shockwave. */
    public record Shockwave(UUID strikeId, double x, double y, double z, float startRadius, float endRadius,
                            int durationTicks) implements CustomPayload {
        public static final Id<Shockwave> ID = id("shockwave");
        public static final PacketCodec<RegistryByteBuf, Shockwave> CODEC = PacketCodec.of(
                (v, buf) -> {
                    buf.writeUuid(v.strikeId);
                    buf.writeDouble(v.x);
                    buf.writeDouble(v.y);
                    buf.writeDouble(v.z);
                    buf.writeFloat(v.startRadius);
                    buf.writeFloat(v.endRadius);
                    buf.writeVarInt(v.durationTicks);
                },
                buf -> new Shockwave(buf.readUuid(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readFloat(), buf.readFloat(), buf.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Remaining cooldown and server limits for the menu UI. */
    public record CooldownSync(int remainingTicks, int totalTicks, int maxRange, int maxPower) implements CustomPayload {
        public static final Id<CooldownSync> ID = id("cooldown_sync");
        public static final PacketCodec<RegistryByteBuf, CooldownSync> CODEC = PacketCodec.of(
                (v, buf) -> {
                    buf.writeVarInt(v.remainingTicks);
                    buf.writeVarInt(v.totalTicks);
                    buf.writeVarInt(v.maxRange);
                    buf.writeVarInt(v.maxPower);
                },
                buf -> new CooldownSync(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
