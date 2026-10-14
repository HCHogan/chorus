package com.imdomestic.chorus.effect.actor;

import static com.imdomestic.chorus.core.codec.DataCodecs.*;
import com.imdomestic.chorus.stat.codec.StatCodecs;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;

/** Public typed configuration, independent of the internal checkpoint record encoding. */
public final class ActorCodecs {
    private ActorCodecs() {}
    public static final Codec<ActorBinding> BINDING = strict(RecordCodecBuilder.create(i -> i.group(
            ID.fieldOf("bundle").forGetter(ActorBinding::bundle),
            ID.listOf().xmap(Set::copyOf, tags -> tags.stream().sorted().toList()).optionalFieldOf("tags", Set.of()).forGetter(ActorBinding::tags),
            Codec.unboundedMap(Codec.STRING, StatCodecs.MEASURE).optionalFieldOf("parameters", Map.of()).forGetter(ActorBinding::parameters)
    ).apply(i, ActorBinding::new)), Set.of("bundle", "tags", "parameters"));
}
