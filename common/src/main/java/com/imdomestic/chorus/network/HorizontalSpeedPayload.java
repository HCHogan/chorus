package com.imdomestic.chorus.network;

import com.imdomestic.chorus.stat.Numbers;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record HorizontalSpeedPayload(String dimension,int entityId,UUID uuid,OptionalDouble metersPerSecond) implements CustomPacketPayload {
    public static final Type<HorizontalSpeedPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("chorus","horizontal_speed_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf,HorizontalSpeedPayload> CODEC=StreamCodec.ofMember(HorizontalSpeedPayload::write,HorizontalSpeedPayload::read);
    public HorizontalSpeedPayload{
        if(dimension==null||dimension.length()>512||entityId<0)throw new IllegalArgumentException("Invalid horizontal speed identity");Identifier.parse(dimension);Objects.requireNonNull(uuid);Objects.requireNonNull(metersPerSecond);metersPerSecond.ifPresent(v->Numbers.nonnegative(v,"horizontal speed limit"));
    }
    private void write(RegistryFriendlyByteBuf buffer){buffer.writeUtf(dimension,512);buffer.writeVarInt(entityId);buffer.writeUUID(uuid);buffer.writeBoolean(metersPerSecond.isPresent());metersPerSecond.ifPresent(buffer::writeDouble);}
    private static HorizontalSpeedPayload read(RegistryFriendlyByteBuf buffer){return new HorizontalSpeedPayload(buffer.readUtf(512),buffer.readVarInt(),buffer.readUUID(),buffer.readBoolean()?OptionalDouble.of(buffer.readDouble()):OptionalDouble.empty());}
    @Override public Type<HorizontalSpeedPayload> type(){return TYPE;}
}
