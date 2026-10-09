package com.imdomestic.chorus.effect;

import com.imdomestic.chorus.effect.buff.BuffRules;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Source-owned scheduled facts. Ownership is independent of the combat source carried by the event. */
public final class EffectTimers {
    private EffectTimers() {}
    public record Owner(Optional<EffectSource> source, Optional<EffectState.Lifetime> buff) {
        public Owner {
            Objects.requireNonNull(source); Objects.requireNonNull(buff);
            if (source.isPresent() == buff.isPresent()) throw new IllegalArgumentException("A timer needs exactly one owner");
        }
        public static Owner of(RuleEngine.Payload scope) {
            if (scope instanceof EffectSource source) return new Owner(Optional.of(source), Optional.empty());
            if (scope instanceof BuffRules.Scope buff) return new Owner(Optional.empty(),
                    Optional.of(new EffectState.Lifetime(buff.snapshot().key(), buff.snapshot().generation())));
            throw new IllegalArgumentException("Timer requires a bound source");
        }
        public boolean active(Map<String, EffectSource> sources, BuffStore buffs) {
            return source.map(value -> value.equals(sources.get(value.instance()))).orElseGet(() -> buff.orElseThrow().active(buffs));
        }
        public String key(String name) {
            localName(name);
            return source.map(value -> "source/" + value.instance().length() + ":" + value.instance())
                    .orElseGet(() -> "buff/" + buff.orElseThrow().generation()) + "/timer/" + name;
        }
    }
    public record Scheduled(String name, Owner owner, EffectEvent event) implements RuleEngine.Payload {
        public Scheduled { localName(name); Objects.requireNonNull(owner); Objects.requireNonNull(event); }
    }
    public static void localName(String name) {
        if (name == null || !name.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid local timer name: " + name);
    }
    public static Optional<EffectEvent> event(RuleEngine.Payload payload) {
        if (payload instanceof EffectEvent event) return Optional.of(event);
        if (payload instanceof Scheduled scheduled) return Optional.of(scheduled.event());
        if (payload instanceof EffectEvent.Carrier carrier) return Optional.of(carrier.event());
        return Optional.empty();
    }
    public static boolean active(EffectState.Timer timer, Map<String, EffectSource> sources, BuffStore buffs) {
        if (timer.signal().payload() instanceof EffectContinuations.Pending pending) return pending.owner().map(value -> value.active(sources, buffs)).orElse(true);
        return !(timer.signal().payload() instanceof Scheduled scheduled) || scheduled.owner().active(sources, buffs);
    }
}
