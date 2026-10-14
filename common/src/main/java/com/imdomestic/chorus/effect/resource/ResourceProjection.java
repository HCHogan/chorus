package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.data.EffectProgram;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** An exact, settled account partition write. The coordinating host owns cross-runtime authority. */
public final class ResourceProjection {
    private ResourceProjection() {}
    public static Map<String, ResourceState> accounts(EffectState state, String holder) {
        holder(holder);
        var result = new TreeMap<String, ResourceState>();
        state.resources().forEach((key, value) -> { if (key.holder().equals(holder)) result.put(key.resource(), value); });
        return Collections.unmodifiableMap(result);
    }
    public static Map<String, ResourceState> checked(String holder, long time, Map<String, ResourceState> accounts) {
        holder(holder); if (time < 0 || time == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid resource partition clock");
        var copy = new TreeMap<>(accounts);
        copy.forEach((id, value) -> {
            if (!id.equals(value.key().resource()) || !holder.equals(value.key().holder()) || value.timeMicros() != time)
                throw new IllegalArgumentException("Resource partition identity or clock mismatch");
        });
        return Collections.unmodifiableMap(copy);
    }
    static void holder(String holder) { if (holder == null || holder.isBlank()) throw new IllegalArgumentException("Missing resource holder"); }
    public static void validate(CompiledEffects program, Map<String, ResourceState> accounts) {
        validate(program.program(), accounts);
    }
    public static void validate(EffectProgram program, Map<String, ResourceState> accounts) {
        var definitions = new HashMap<String, ResourceDefinition>();
        program.resources().forEach(value -> {
            if (definitions.put(value.id(), value) != null) throw new IllegalArgumentException("Duplicate resource definition");
        });
        for (var account : accounts.values()) {
            var definition = definitions.get(account.key().resource());
            if (definition == null) throw new IllegalArgumentException("Resource is not in the pinned catalogue: " + account.key().resource());
            definition.validate(account);
        }
    }
    public record Change(String holder, long timeMicros, Map<String, ResourceState> before, Map<String, ResourceState> after)
            implements RuleEngine.Payload {
        public Change { before = checked(holder, timeMicros, before); after = checked(holder, timeMicros, after); }
        public EffectState apply(EffectState state, CompiledEffects program) {
            if (state.buffs().timeMicros() != timeMicros || !accounts(state, holder).equals(before))
                throw new IllegalStateException("Stale resource partition projection");
            validate(program, before); validate(program, after);
            var resources = new HashMap<>(state.resources());
            resources.keySet().removeIf(key -> key.holder().equals(holder));
            after.values().forEach(value -> resources.put(value.key(), value));
            return state.withResources(resources);
        }
    }
}
