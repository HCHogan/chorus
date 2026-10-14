package com.imdomestic.chorus.effect.actor;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import java.util.*;

/** One stable, managed root source per owner, independent of ability and equipment slots. */
public final class ActorSources {
    public static final String PREFIX = "actor/";
    private ActorSources() {}
    public static String instance(String holder) {
        if (holder == null || holder.isBlank()) throw new IllegalArgumentException("Missing actor holder");
        return PREFIX + holder.length() + ":" + holder;
    }
    public static EffectSource source(String holder, ActorBinding binding) {
        String id = instance(holder);
        return new EffectSource(id, binding.bundle(), holder, new BuffInstance.Origin(holder, id, "", ""), binding.tags(), binding.parameters());
    }
    public static Map<String, EffectSource> sources(String holder, Optional<ActorBinding> binding) {
        String id = instance(holder);
        return binding.map(value -> Map.of(id, source(holder, value))).orElse(Map.of());
    }
    public static void validate(EffectState state) {
        var expected = new TreeMap<String, EffectSource>();
        state.actors().forEach((holder, binding) -> expected.put(instance(holder), source(holder, binding)));
        var actual = new TreeMap<String, EffectSource>();
        state.sources().forEach((id, source) -> { if (id.startsWith(PREFIX)) actual.put(id, source); });
        if (!expected.equals(actual)) throw new IllegalStateException("Actor source projection is inconsistent");
    }
}
