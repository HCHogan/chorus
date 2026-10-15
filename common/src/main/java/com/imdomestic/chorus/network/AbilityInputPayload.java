package com.imdomestic.chorus.network;

import com.imdomestic.chorus.effect.ability.*;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** No client duration, cost, ability definition, target or player identity is accepted. */
public record AbilityInputPayload(long sequence, long gesture, String dimension, String slot, Edge edge,long transferRevision) implements CustomPacketPayload {
    /** HOLD is transport authority only; it never enters the persisted ability edge model. */
    public enum Edge { PRESS, HOLD, RELEASE, CANCEL }
    public static final Type<AbilityInputPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("chorus", "ability_input_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AbilityInputPayload> CODEC = StreamCodec.ofMember(AbilityInputPayload::write, AbilityInputPayload::read);
    public AbilityInputPayload {
        Objects.requireNonNull(edge);
        if (sequence <= 0 || gesture <= 0 || gesture > sequence || transferRevision<0 || edge == Edge.PRESS && gesture != sequence
                || dimension == null || dimension.length() > 512 || slot == null || slot.length() > 512) throw new IllegalArgumentException("Invalid ability input");
        Identifier.parse(dimension); AbilityDefinition.id(slot);
    }
    public AbilityInputPayload(long sequence,long gesture,String dimension,String slot,Edge edge){this(sequence,gesture,dimension,slot,edge,0);}
    public AbilityInputPayload(long sequence,long gesture,String dimension,String slot,AbilityInput.Edge edge){this(sequence,gesture,dimension,slot,Edge.valueOf(edge.name()),0);}
    public AbilityInputPayload(long sequence,long gesture,String dimension,String slot,AbilityInput.Edge edge,long transferRevision){this(sequence,gesture,dimension,slot,Edge.valueOf(edge.name()),transferRevision);}
    private void write(RegistryFriendlyByteBuf b) { b.writeVarLong(sequence); b.writeVarLong(gesture); b.writeUtf(dimension, 512); b.writeUtf(slot, 512); b.writeEnum(edge);b.writeVarLong(transferRevision); }
    private static AbilityInputPayload read(RegistryFriendlyByteBuf b) { return new AbilityInputPayload(b.readVarLong(), b.readVarLong(), b.readUtf(512), b.readUtf(512), b.readEnum(Edge.class),b.readVarLong()); }
    @Override public Type<AbilityInputPayload> type() { return TYPE; }
}
