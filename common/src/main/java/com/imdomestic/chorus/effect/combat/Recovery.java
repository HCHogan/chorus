package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** Piecewise-constant health recovery. Group selection precedes integration and keeps the winning source. */
public final class Recovery {
    private Recovery() {}
    public static final String EVENT = "chorus:internal/recovery";
    private static final String RELEASE = "chorus:internal/recovery_release";
    public static final long QUANTUM_MICROS = 50_000;
    public record Offer(String id, String target, String channel, int priority, double perSecond,
            BuffInstance.Origin source, Set<String> tags) {
        public Offer {
            if (id.isBlank() || target.isBlank() || channel.isBlank()) throw new IllegalArgumentException("Missing recovery identity");
            Numbers.nonnegative(perSecond, "health recovery rate"); Objects.requireNonNull(source); tags = Set.copyOf(tags);
        }
    }
    public record Allocation(Offer offer, long from, long until, HealingCommand command) implements RuleEngine.Payload {}
    public record Batch(List<Allocation> allocations, List<Offer> suppressed, List<RuleEngine.Signal> following) implements RuleEngine.Payload {
        public Batch { allocations = List.copyOf(allocations); suppressed = List.copyOf(suppressed); following = List.copyOf(following); }
    }
    private record Key(String target, String channel) implements Comparable<Key> {
        @Override public int compareTo(Key other) { int targetOrder = target.compareTo(other.target); return targetOrder == 0 ? channel.compareTo(other.channel) : targetOrder; }
    }
    /** Higher priority wins first, then higher rate, then stable identity. Other channels remain independent. */
    public static Batch integrate(List<Offer> offers, long from, long until, List<RuleEngine.Signal> following) {
        if (from < 0 || until <= from || until == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid recovery interval");
        var ids = new HashSet<String>(); var groups = new TreeMap<Key, List<Offer>>();
        for (var offer : offers) {
            if (!ids.add(offer.id())) throw new IllegalArgumentException("Duplicate recovery identity: " + offer.id());
            if (offer.perSecond() > 0) groups.computeIfAbsent(new Key(offer.target(), offer.channel()), _ -> new ArrayList<>()).add(offer);
        }
        var selected = new ArrayList<Allocation>(); var suppressed = new ArrayList<Offer>();
        var order = Comparator.comparingInt(Offer::priority).reversed()
                .thenComparing(Comparator.comparingDouble(Offer::perSecond).reversed()).thenComparing(Offer::id);
        for (var group : groups.values()) {
            group.sort(order); var winner = group.getFirst(); suppressed.addAll(group.subList(1, group.size()));
            double amount = winner.perSecond() * ((until - from) / 1_000_000.0);
            var tags = new HashSet<>(winner.tags()); tags.add("chorus:continuous_healing");
            selected.add(new Allocation(winner, from, until, new HealingCommand(winner.target(), winner.source(), amount, tags)));
        }
        return new Batch(selected, suppressed, following);
    }
    public static List<RuleEngine.EventRule<EffectState>> definitions() {
        var heal = new RuleEngine.Action<EffectState>() {
            @Override public RuleEngine.Outcome<EffectState> step(EffectState state, RuleEngine.Context context) {
                return new RuleEngine.Await<>(((Allocation) context.scope()).command());
            }
            @Override public RuleEngine.Local<EffectState> complete(EffectState state, RuleEngine.Context context, RuleEngine.ActionResult result) {
                var receipt = (HealingReceipt) result;
                if (!receipt.command().equals(((Allocation) context.scope()).command())) throw new IllegalArgumentException("Recovery receipt differs from its allocated interval");
                return new RuleEngine.Local<>(state, receipt, HealingFacts.from(receipt));
            }
        };
        return List.of(new RuleEngine.EventRule<>(EVENT, EVENT, (_, _) -> true, List.of(new RuleEngine.Instruction<>(heal, ""))),
                new RuleEngine.EventRule<>(RELEASE, EVENT, (_, _) -> true, List.of(new RuleEngine.Instruction<>((state, context) ->
                        new RuleEngine.Local<>(state, RuleEngine.Empty.INSTANCE, ((Batch) context.scope()).following()), ""))));
    }
    /** All interval commands finish before their resulting facts and the boundary's other facts are distributed. */
    public static List<RuleEngine.RuleBinding> resolve(RuleEngine.Event event) {
        var batch = (Batch) event.signal().payload(); var bindings = new ArrayList<RuleEngine.RuleBinding>();
        for (var allocation : batch.allocations()) bindings.add(new RuleEngine.RuleBinding(EVENT + "/" + allocation.offer().id(), EVENT, allocation));
        bindings.add(new RuleEngine.RuleBinding(RELEASE, RELEASE, batch)); return List.copyOf(bindings);
    }
}
