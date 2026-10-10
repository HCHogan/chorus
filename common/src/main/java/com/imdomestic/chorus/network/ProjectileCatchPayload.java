package com.imdomestic.chorus.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A connection-local input, with no client-authoritative receiver, position or projectile identity. */
public record ProjectileCatchPayload(long sequence, String dimension) implements CustomPacketPayload {
    public static final Type<ProjectileCatchPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("chorus", "projectile_catch"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectileCatchPayload> CODEC = StreamCodec.ofMember(ProjectileCatchPayload::write, ProjectileCatchPayload::read);
    public ProjectileCatchPayload {
        if (sequence <= 0 || dimension == null || dimension.length() > 512) throw new IllegalArgumentException("Invalid catch input");
        Identifier.parse(dimension);
    }
    private void write(RegistryFriendlyByteBuf buffer) { buffer.writeVarLong(sequence); buffer.writeUtf(dimension, 512); }
    private static ProjectileCatchPayload read(RegistryFriendlyByteBuf buffer) { return new ProjectileCatchPayload(buffer.readVarLong(), buffer.readUtf(512)); }
    @Override public Type<ProjectileCatchPayload> type() { return TYPE; }
}
