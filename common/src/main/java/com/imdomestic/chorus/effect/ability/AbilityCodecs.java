package com.imdomestic.chorus.effect.ability;

import static com.imdomestic.chorus.core.codec.DataCodecs.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Set;

/** Player configuration encoding, separate from internal checkpoint identities. */
public final class AbilityCodecs {
    private AbilityCodecs() {}
    public static final Codec<AbilityLoadout> LOADOUT = strict(RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(ID, ID).fieldOf("slots").forGetter(AbilityLoadout::slots)
    ).apply(i, AbilityLoadout::new)), Set.of("slots"));
}
