package com.imdomestic.chorus.network;

import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Explicit server authorization to carry one existing physical gesture across a native dimension transition. */
public record InputTransferPayload(Kind kind,String slot,UUID player,int entity,String fromDimension,String toDimension,
        long sequence,long gesture,long revision,long previousRevision) implements CustomPacketPayload {
    public enum Kind { AIM, CHARGE, ABILITY }
    public static final Type<InputTransferPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("chorus","input_transfer"));
    public static final StreamCodec<RegistryFriendlyByteBuf,InputTransferPayload> CODEC=StreamCodec.ofMember(InputTransferPayload::write,InputTransferPayload::read);
    public InputTransferPayload {
        Objects.requireNonNull(kind);Objects.requireNonNull(slot);Objects.requireNonNull(player);
        Objects.requireNonNull(fromDimension);Objects.requireNonNull(toDimension);
        if(fromDimension.length()>512||toDimension.length()>512||slot.length()>512||fromDimension.equals(toDimension)
                ||sequence<=0||gesture<=0||gesture>sequence||previousRevision<0||revision<=previousRevision)throw new IllegalArgumentException("Invalid input transfer");
        Identifier.parse(fromDimension);Identifier.parse(toDimension);
        if(kind==Kind.ABILITY)Identifier.parse(slot);else if(!slot.isEmpty())throw new IllegalArgumentException("Weapon gesture has no ability slot");
    }
    public InputTransferPayload(Kind kind,String slot,UUID player,int entity,String fromDimension,String toDimension,long sequence,long gesture,long revision){this(kind,slot,player,entity,fromDimension,toDimension,sequence,gesture,revision,0);}
    private void write(RegistryFriendlyByteBuf b){b.writeEnum(kind);b.writeUtf(slot,512);b.writeUUID(player);b.writeVarInt(entity);b.writeUtf(fromDimension,512);b.writeUtf(toDimension,512);b.writeVarLong(sequence);b.writeVarLong(gesture);b.writeVarLong(revision);b.writeVarLong(previousRevision);}
    private static InputTransferPayload read(RegistryFriendlyByteBuf b){return new InputTransferPayload(b.readEnum(Kind.class),b.readUtf(512),b.readUUID(),b.readVarInt(),b.readUtf(512),b.readUtf(512),b.readVarLong(),b.readVarLong(),b.readVarLong(),b.readVarLong());}
    @Override public Type<InputTransferPayload> type(){return TYPE;}
}
