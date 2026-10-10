package com.imdomestic.chorus.network;

import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-only decision; UUID and dimension prevent application to a reused entity ID or another world. */
public record MovementInputPayload(String dimension,int entityId,UUID uuid,int mask) implements CustomPacketPayload {
    public static final Type<MovementInputPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("chorus","movement_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf,MovementInputPayload> CODEC=StreamCodec.ofMember(MovementInputPayload::write,MovementInputPayload::read);
    public MovementInputPayload {
        if(dimension==null||dimension.length()>512||entityId<0||mask<0||mask>3)throw new IllegalArgumentException("Invalid movement input projection");
        Identifier.parse(dimension);Objects.requireNonNull(uuid);
    }
    private void write(RegistryFriendlyByteBuf buffer){buffer.writeUtf(dimension,512);buffer.writeVarInt(entityId);buffer.writeUUID(uuid);buffer.writeByte(mask);}
    private static MovementInputPayload read(RegistryFriendlyByteBuf buffer){return new MovementInputPayload(buffer.readUtf(512),buffer.readVarInt(),buffer.readUUID(),buffer.readUnsignedByte());}
    @Override public Type<MovementInputPayload> type(){return TYPE;}
}
