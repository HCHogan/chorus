package com.imdomestic.chorus.network;

import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Input has no client weapon, duration, player identity or calculated perk state. */
public final class WeaponAimPayloads {
    private WeaponAimPayloads() {}
    public enum Edge { PRESS, HOLD, RELEASE, CANCEL }
    public enum Phase { OFF, ENTERING, AIMED, EXITING }
    public enum Status { IDLE, ACTIVE, BUSY, RESTRICTED, UNAVAILABLE, FAILED }
    private static void dimension(String value) {
        if (value == null || value.length() > 512) throw new IllegalArgumentException("Invalid aim dimension");
        Identifier.parse(value);
    }
    public record Input(long sequence, long gesture, String dimension, Edge edge) implements CustomPacketPayload {
        public static final Type<Input> TYPE = new Type<>(Identifier.fromNamespaceAndPath("chorus", "weapon_aim_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.ofMember(Input::write, Input::read);
        public Input {
            Objects.requireNonNull(edge); WeaponAimPayloads.dimension(dimension);
            if (sequence <= 0 || gesture <= 0 || gesture > sequence || edge == Edge.PRESS && sequence != gesture)
                throw new IllegalArgumentException("Invalid aim input sequence");
        }
        private void write(RegistryFriendlyByteBuf b) { b.writeVarLong(sequence); b.writeVarLong(gesture); b.writeUtf(dimension, 512); b.writeEnum(edge); }
        private static Input read(RegistryFriendlyByteBuf b) { return new Input(b.readVarLong(), b.readVarLong(), b.readUtf(512), b.readEnum(Edge.class)); }
        @Override public Type<Input> type() { return TYPE; }
    }
    /** Revision orders unsolicited phase changes as well as input acknowledgements. Times are server logical microseconds. */
    public record View(long revision, long sequence, long gesture, UUID player, String dimension, boolean held,
            Status status, Phase phase, String weapon, long startedAt, long dueAt) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(Identifier.fromNamespaceAndPath("chorus", "weapon_aim_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.ofMember(View::write, View::read);
        public View {
            Math.subtractExact(dueAt,startedAt);
            Objects.requireNonNull(player); Objects.requireNonNull(status); Objects.requireNonNull(phase); Objects.requireNonNull(weapon);
            WeaponAimPayloads.dimension(dimension);
            if (revision <= 0 || sequence <= 0 || gesture < 0 || gesture > sequence || held && gesture == 0 || weapon.length() > 512
                    || dueAt < startedAt || (phase == Phase.OFF ? !weapon.isEmpty() || startedAt != 0 || dueAt != 0 : weapon.isBlank()))
                throw new IllegalArgumentException("Invalid aim projection");
        }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeVarLong(revision); b.writeVarLong(sequence); b.writeVarLong(gesture); b.writeUUID(player); b.writeUtf(dimension, 512);
            b.writeBoolean(held); b.writeEnum(status); b.writeEnum(phase); b.writeUtf(weapon, 512); b.writeVarLong(startedAt); b.writeVarLong(dueAt);
        }
        private static View read(RegistryFriendlyByteBuf b) {
            return new View(b.readVarLong(), b.readVarLong(), b.readVarLong(), b.readUUID(), b.readUtf(512), b.readBoolean(),
                    b.readEnum(Status.class), b.readEnum(Phase.class), b.readUtf(512), b.readVarLong(), b.readVarLong());
        }
        @Override public Type<View> type() { return TYPE; }
    }
}
