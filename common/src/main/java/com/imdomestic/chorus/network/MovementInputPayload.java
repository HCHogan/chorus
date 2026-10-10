package com.imdomestic.chorus.network;

import java.util.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-only decision; UUID and dimension prevent application to a reused entity ID or another world. */
public record MovementInputPayload(String dimension,int entityId,UUID uuid,int mask,Vec3 anchor) implements CustomPacketPayload {
    public static final Type<MovementInputPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("chorus","movement_input_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf,MovementInputPayload> CODEC=StreamCodec.ofMember(MovementInputPayload::write,MovementInputPayload::read);
    public MovementInputPayload(String dimension,int entityId,UUID uuid,int mask){this(dimension,entityId,uuid,mask,Vec3.ZERO);}
    public MovementInputPayload {
        if(dimension==null||dimension.length()>512||entityId<0||mask<0||mask>15)throw new IllegalArgumentException("Invalid movement input projection");
        Identifier.parse(dimension);Objects.requireNonNull(uuid);Objects.requireNonNull(anchor);
        if(!anchor.isFinite()||Math.abs(anchor.x)>3.1e7||Math.abs(anchor.y)>2.1e7||Math.abs(anchor.z)>3.1e7)throw new IllegalArgumentException("Invalid motion anchor");
        if((mask&12)==0&&!anchor.equals(Vec3.ZERO))throw new IllegalArgumentException("Unconstrained input has an anchor");
    }
    private void write(RegistryFriendlyByteBuf buffer){buffer.writeUtf(dimension,512);buffer.writeVarInt(entityId);buffer.writeUUID(uuid);buffer.writeByte(mask);if((mask&12)!=0){buffer.writeDouble(anchor.x);buffer.writeDouble(anchor.y);buffer.writeDouble(anchor.z);}}
    private static MovementInputPayload read(RegistryFriendlyByteBuf buffer){
        String dimension=buffer.readUtf(512);int id=buffer.readVarInt();var uuid=buffer.readUUID();int mask=buffer.readUnsignedByte();
        var anchor=(mask&12)!=0?new Vec3(buffer.readDouble(),buffer.readDouble(),buffer.readDouble()):Vec3.ZERO;
        return new MovementInputPayload(dimension,id,uuid,mask,anchor);
    }
    @Override public Type<MovementInputPayload> type(){return TYPE;}
}
