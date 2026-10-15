package com.imdomestic.chorus.network;

import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Input has no client weapon, duration, player identity or calculated perk state. */
public final class WeaponChargePayloads {
    private WeaponChargePayloads() {}
    public enum Edge { PRESS, HOLD, RELEASE, CANCEL }
    public enum Phase { OFF, CHARGING, READY, RECOVERING }
    public enum Status { IDLE, ACTIVE, FIRED, CANCELLED, REJECTED, UNAVAILABLE, FAILED }
    private static void dimension(String value) {
        if (value == null || value.length() > 512) throw new IllegalArgumentException("Invalid charge dimension");
        Identifier.parse(value);
    }
    public record Input(long sequence, long gesture, String dimension, Edge edge,long transferRevision) implements CustomPacketPayload {
        public static final Type<Input> TYPE = new Type<>(Identifier.fromNamespaceAndPath("chorus", "weapon_charge_input_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.ofMember(Input::write, Input::read);
        public Input {
            Objects.requireNonNull(edge); WeaponChargePayloads.dimension(dimension);
            if (sequence <= 0 || gesture <= 0 || gesture > sequence || transferRevision<0 || edge == Edge.PRESS && sequence != gesture)
                throw new IllegalArgumentException("Invalid charge input sequence");
        }
        public Input(long sequence,long gesture,String dimension,Edge edge){this(sequence,gesture,dimension,edge,0);}
        private void write(RegistryFriendlyByteBuf b) { b.writeVarLong(sequence); b.writeVarLong(gesture); b.writeUtf(dimension, 512); b.writeEnum(edge);b.writeVarLong(transferRevision); }
        private static Input read(RegistryFriendlyByteBuf b) { return new Input(b.readVarLong(), b.readVarLong(), b.readUtf(512), b.readEnum(Edge.class),b.readVarLong()); }
        @Override public Type<Input> type() { return TYPE; }
    }
    /** Server samples are display-only. Native entity ids can survive respawn; clients must also bind a gesture to their player object. */
    public record View(long revision, long sequence, long gesture, UUID player, int entity, String dimension, boolean held,
            Status status, Phase phase, String weapon, long startedAt, long minimumAt, long fullAt, long observedAt) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(Identifier.fromNamespaceAndPath("chorus", "weapon_charge_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.ofMember(View::write, View::read);
        public View {
            Math.subtractExact(fullAt,startedAt); Math.subtractExact(observedAt,startedAt);
            Objects.requireNonNull(player); Objects.requireNonNull(status); Objects.requireNonNull(phase); Objects.requireNonNull(weapon);
            WeaponChargePayloads.dimension(dimension);
            if (revision <= 0 || sequence <= 0 || gesture < 0 || gesture > sequence || held && gesture == 0 || weapon.length() > 512
                    || minimumAt < startedAt || fullAt < minimumAt || observedAt < 0
                    || (phase == Phase.OFF ? !weapon.isEmpty() || startedAt != 0 || minimumAt != 0 || fullAt != 0 || held
                        : weapon.isBlank() || fullAt <= startedAt || observedAt < startedAt || !held || status != Status.ACTIVE)
                    || phase == Phase.READY && observedAt < fullAt || (phase == Phase.CHARGING || phase == Phase.RECOVERING) && observedAt >= fullAt)
                throw new IllegalArgumentException("Invalid charge projection");
        }
        public double fraction() { return phase == Phase.OFF ? 0 : Math.min(1, (observedAt - startedAt) / (double) (fullAt - startedAt)); }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeVarLong(revision); b.writeVarLong(sequence); b.writeVarLong(gesture); b.writeUUID(player); b.writeVarInt(entity); b.writeUtf(dimension, 512);
            b.writeBoolean(held); b.writeEnum(status); b.writeEnum(phase); b.writeUtf(weapon, 512);
            b.writeVarLong(startedAt); b.writeVarLong(minimumAt); b.writeVarLong(fullAt); b.writeVarLong(observedAt);
        }
        private static View read(RegistryFriendlyByteBuf b) {
            return new View(b.readVarLong(), b.readVarLong(), b.readVarLong(), b.readUUID(), b.readVarInt(), b.readUtf(512), b.readBoolean(),
                    b.readEnum(Status.class), b.readEnum(Phase.class), b.readUtf(512), b.readVarLong(), b.readVarLong(), b.readVarLong(), b.readVarLong());
        }
        @Override public Type<View> type() { return TYPE; }
    }
}
