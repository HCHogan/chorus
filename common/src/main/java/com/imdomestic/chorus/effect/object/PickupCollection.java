package com.imdomestic.chorus.effect.object;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** A sampled candidate list followed by separately confirmed claims and their original reward bodies. */
public final class PickupCollection {
    public static final String EVENT = "chorus:internal/collect_pickup";
    // Compiler-owned slots cannot be produced by a DSL local name/path.
    public static final String BATCH_SLOT = "/chorus/pickup_batch", CLAIM_SLOT = "/chorus/pickup_claim";
    private PickupCollection() {}
    public record Query(String collector, double radius, Set<String> kinds, boolean lineOfSight) implements RuleEngine.WorldCommand {
        public Query {
            Objects.requireNonNull(collector); Numbers.nonnegative(radius, "pickup collection radius"); kinds = Set.copyOf(kinds);
            if (kinds.isEmpty()) throw new IllegalArgumentException("Pickup collection requires explicit kinds");
            kinds.forEach(com.imdomestic.chorus.effect.ability.AbilityDefinition::id);
        }
    }
    public record Entry(String entity, WorldPickup.Spawn spawn) {
        public Entry { Objects.requireNonNull(entity); Objects.requireNonNull(spawn); if (entity.isBlank()) throw new IllegalArgumentException("Missing pickup identity"); }
    }
    public enum Outcome { FOUND, MISSING_COLLECTOR }
    public record Result(Query query, Outcome outcome, List<Entry> entries) implements RuleEngine.ActionResult {
        public Result {
            Objects.requireNonNull(query); Objects.requireNonNull(outcome); entries = PickupCollection.entries(query, entries);
            if (outcome != Outcome.FOUND && !entries.isEmpty()) throw new IllegalArgumentException("Missing collector has pickup candidates");
        }
    }
    private static List<Entry> entries(Query query, List<Entry> values) {
        var entries = List.copyOf(values); var ids = new HashSet<String>();
        for (var entry : entries) if (!ids.add(entry.entity()) || !query.collector().equals(entry.spawn().recipient()) || !query.kinds().contains(entry.spawn().kind()))
            throw new IllegalArgumentException("Invalid or duplicate pickup candidate");
        return entries;
    }
    public record Batch(Query query, List<Entry> entries) implements RuleEngine.Payload, RuleEngine.ActionResult {
        public Batch { Objects.requireNonNull(query); entries = PickupCollection.entries(query, entries); if (entries.isEmpty()) throw new IllegalArgumentException("Empty collection batch"); }
        public Entry first() { return entries.getFirst(); }
        public List<RuleEngine.Signal> next() { return entries.size() == 1 ? List.of() : List.of(new RuleEngine.Signal(EVENT, new Batch(query, entries.subList(1, entries.size())))); }
        public EffectContinuations.Pending scope() {
            var p = first().spawn().continuation(); var bindings = new HashMap<>(p.bindings());
            if (bindings.putIfAbsent(BATCH_SLOT, this) != null) throw new IllegalArgumentException("Reserved pickup binding");
            return new EffectContinuations.Pending(p.id(), p.definition(), p.version(), p.scope(), p.cause(), bindings, p.owner());
        }
    }
    public record Claim(Query query, Entry entry) implements RuleEngine.WorldCommand {
        public Claim { Objects.requireNonNull(query); Objects.requireNonNull(entry); entries(query, List.of(entry)); }
    }
    public enum ClaimOutcome { COLLECTED, UNAVAILABLE, EXPIRED, MISSING_COLLECTOR, OUT_OF_RANGE, OCCLUDED, INELIGIBLE }
    public record Claimed(Claim claim, ClaimOutcome outcome, Optional<WorldPickup.Contact> contact) implements RuleEngine.ActionResult {
        public Claimed {
            Objects.requireNonNull(claim); Objects.requireNonNull(outcome); Objects.requireNonNull(contact);
            if ((outcome == ClaimOutcome.COLLECTED) != contact.isPresent()) throw new IllegalArgumentException("Collection result lacks its confirmed contact");
            contact.ifPresent(value -> { if (value.end() != WorldPickup.End.COLLECTED) throw new IllegalArgumentException("Remote claim cannot report expiry"); claim.entry().spawn().finish(value); });
        }
    }
    public static Batch batch(RuleEngine.Context context) { return (Batch) ((EffectContinuations.Pending) context.scope()).bindings().get(BATCH_SLOT); }
}
