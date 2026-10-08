package com.imdomestic.chorus.item;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.function.IntFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

public enum EngramRarity implements StringRepresentable {
    COMMON(0, "common", ChatFormatting.WHITE),
    UNCOMMON(1, "uncommon", ChatFormatting.GREEN),
    RARE(2, "rare", ChatFormatting.AQUA),
    LEGENDARY(3, "legendary", ChatFormatting.LIGHT_PURPLE),
    EXOTIC(4, "exotic", ChatFormatting.GOLD);

    public static final Codec<EngramRarity> CODEC = StringRepresentable.fromValues(EngramRarity::values);

    private static final IntFunction<EngramRarity> BY_ID = ByIdMap.continuous(r -> r.id, values(),
            ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, EngramRarity> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, r -> r.id);

    private final int id;
    private final String name;
    private final ChatFormatting color;

    EngramRarity(int id, String name, ChatFormatting color) {
        this.id = id;
        this.name = name;
        this.color = color;
    }

    public ChatFormatting color() {
        return color;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
