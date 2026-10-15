package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.ability.AbilityUse;
import com.imdomestic.chorus.effect.ability.AbilityChange;
import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.SourceChange;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Owner-domain ability acceptance. Payment and the ordered start/on-use facts publish in one coordination batch. */
public final class AbilityResources {
    private AbilityResources() {}
    public record Use(AbilityUse.Request ability) implements ResourceRequests.Request {
        public Use { Objects.requireNonNull(ability); }
    }
    public record Select(AbilityChange change) implements ResourceRequests.Request {
        public Select { Objects.requireNonNull(change); }
    }
    public record Selected(Select request, List<RuleEngine.Signal> facts, ResourceAuthority.Lease lease) implements ResourceRequests.Receipt {
        public Selected {
            Objects.requireNonNull(request); facts = List.copyOf(facts); Objects.requireNonNull(lease);
            boolean changed = !request.change().before().equals(request.change().after());
            if (changed == facts.isEmpty()) throw new IllegalArgumentException("Ability selection facts differ from change");
            for (int i = 0; i < facts.size(); i++) {
                var signal = facts.get(i); EffectEvent event;
                if (i == facts.size() - 1) {
                    if (!signal.type().equals("chorus:abilities_changed") || !(signal.payload() instanceof EffectEvent value)) throw new IllegalArgumentException("Missing ability selection fact");
                    event = value;
                } else if (signal.type().equals("chorus:resource_initialized") && signal.payload() instanceof EffectEvent value) event = value;
                else if (signal.payload() instanceof SourceChange.Fact value && signal.type().equals(value.detached() ? "chorus:source_detached" : "chorus:source_attached")) event = value.event();
                else throw new IllegalArgumentException("Unexpected ability selection fact");
                if (!event.actor().equals(request.change().holder())) throw new IllegalArgumentException("Foreign ability selection fact");
            }
        }
        @Override public Optional<ResourceAuthority.Lease> recipient() { return Optional.of(lease); }
    }
    public record Used(Use request, AbilityUse.Receipt result, Optional<ResourceState> before,
            Optional<AbilityUse.Started> started, ResourceAuthority.Lease lease) implements ResourceRequests.Receipt {
        public Used {
            Objects.requireNonNull(request); Objects.requireNonNull(result); Objects.requireNonNull(before); Objects.requireNonNull(started); Objects.requireNonNull(lease);
            if (!request.ability().cast().equals(result.cast()) || !request.ability().slot().equals(result.slot())
                    || before.isPresent() != result.cost().isPresent() || started.isPresent() != (result.outcome() == AbilityUse.Outcome.ACCEPTED))
                throw new IllegalArgumentException("Ability acceptance differs from request");
            if (result.cost().isPresent()) {
                var cost = result.cost().orElseThrow(); var account = before.orElseThrow();
                if (!account.key().holder().equals(request.ability().holder()) || !account.key().equals(cost.receipt().account())
                        || cost.receipt().refundClaimed() != 0
                        || cost.succeeded() != (result.outcome() == AbilityUse.Outcome.ACCEPTED)
                        || cost.succeeded() && !cost.equals(Resources.spend(account, cost.receipt().operation(), cost.receipt().paid()))
                        || !cost.succeeded() && (result.outcome() != AbilityUse.Outcome.INSUFFICIENT_ENERGY || cost.receipt().paid() != 0 || !account.equals(cost.after())))
                    throw new IllegalArgumentException("Ability payment differs from acceptance");
            } else if (result.outcome() == AbilityUse.Outcome.INSUFFICIENT_ENERGY) throw new IllegalArgumentException("Missing failed ability payment");
            started.ifPresent(fact -> {
                if (!result.equals(fact.receipt()) || !result.resolved().equals(fact.definition().id())
                        || fact.definition().hasPayment() != result.cost().isPresent()
                        || !request.ability().holder().equals(fact.event().actor()) || !request.ability().holder().equals(fact.event().source().owner())
                        || !result.resolved().equals(fact.event().source().ability())) throw new IllegalArgumentException("Ability start differs from acceptance");
            });
        }
        @Override public Optional<ResourceAuthority.Lease> recipient() { return Optional.of(lease); }
    }
    public static List<RuleEngine.Signal> facts(Used used) {
        if (used.started().isEmpty()) return List.of();
        var started = used.started().orElseThrow(); var result = new ArrayList<RuleEngine.Signal>();
        used.result().cost().filter(cost -> cost.receipt().paid() > 0).ifPresent(cost -> {
            var fact = ResourceFacts.change(used.before().orElseThrow(), cost.after(), started.event().source(), "spend");
            result.add(new RuleEngine.Signal("chorus:resource_spent", fact)); result.add(new RuleEngine.Signal("chorus:resource_changed", fact));
        });
        result.add(new RuleEngine.Signal(AbilityUse.STARTED, started));
        result.add(new RuleEngine.Signal("chorus:ability_used", new AbilityUse.Used(started.event(), started.definition(), started.receipt())));
        return List.copyOf(result);
    }
}
