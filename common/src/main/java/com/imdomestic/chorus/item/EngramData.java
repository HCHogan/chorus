package com.imdomestic.chorus.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;

public record EngramData(EngramRarity rarity, int rolls) {
    public static final EngramData DEFAULT = new EngramData(EngramRarity.COMMON, 1);

    public static final Codec<EngramData> CODEC = RecordCodecBuilder.create(i -> i.group(
            EngramRarity.CODEC.fieldOf("rarity").forGetter(EngramData::rarity),
            ExtraCodecs.POSITIVE_INT.optionalFieldOf("rolls", 1).forGetter(EngramData::rolls))
            .apply(i, EngramData::new));

    public static final StreamCodec<ByteBuf, EngramData> STREAM_CODEC = StreamCodec.composite(
            EngramRarity.STREAM_CODEC, EngramData::rarity,
            ByteBufCodecs.VAR_INT, EngramData::rolls,
            EngramData::new);
}
