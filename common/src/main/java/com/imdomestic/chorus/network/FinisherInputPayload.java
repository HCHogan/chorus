package com.imdomestic.chorus.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A connection-local input, with no client-authoritative actor, target, position, eligibility or damage. */
public record FinisherInputPayload(long sequence, String dimension) implements CustomPacketPayload {
    public static final Type<FinisherInputPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("chorus", "finisher_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FinisherInputPayload> CODEC = StreamCodec.ofMember(FinisherInputPayload::write, FinisherInputPayload::read);
    public FinisherInputPayload {
        if (sequence <= 0 || dimension == null || dimension.length() > 512) throw new IllegalArgumentException("Invalid finisher input");
        Identifier.parse(dimension);
    }
    private void write(RegistryFriendlyByteBuf buffer) { buffer.writeVarLong(sequence); buffer.writeUtf(dimension, 512); }
    private static FinisherInputPayload read(RegistryFriendlyByteBuf buffer) { return new FinisherInputPayload(buffer.readVarLong(), buffer.readUtf(512)); }
    @Override public Type<FinisherInputPayload> type() { return TYPE; }
}
