package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** Piecewise-constant recovery. Mixed-unit channel selection waits for world health evidence and retains the winning source. */
public final class Recovery {
    private Recovery() {}
    public static final String EVENT = "chorus:internal/recovery";
    private static final String RELEASE = "chorus:internal/recovery_release";
    public static final long QUANTUM_MICROS = 50_000;
    public record Offer(String id, String target, String channel, int priority, double perSecond,
            BuffInstance.Origin source, Set<String> tags, HealingCommand.Basis basis) {
        public Offer(String id, String target, String channel, int priority, double perSecond, BuffInstance.Origin source, Set<String> tags) {
            this(id, target, channel, priority, perSecond, source, tags, HealingCommand.Basis.ABSOLUTE);
        }
        public Offer {
            if (id.isBlank() || target.isBlank() || channel.isBlank()) throw new IllegalArgumentException("Missing recovery identity");
            Numbers.nonnegative(perSecond, "health recovery rate"); Objects.requireNonNull(source); Objects.requireNonNull(basis); tags = Set.copyOf(tags);
        }
    }
    public record Allocation(String id, long from, long until, RuleEngine.WorldCommand command) implements RuleEngine.Payload {}
    /** Incomparable units in one channel are resolved from one world maximum-health observation. */
    public record Selection(String target, String channel, long from, long until, List<Offer> candidates) implements RuleEngine.WorldCommand {
        public Selection {
            candidates = List.copyOf(candidates);
            if (target.isBlank() || channel.isBlank() || from < 0 || until <= from || until == Long.MAX_VALUE || candidates.isEmpty()) throw new IllegalArgumentException("Invalid recovery selection");
            var ids = new HashSet<String>();
            for (var offer : candidates) if (!offer.target().equals(target) || !offer.channel().equals(channel) || !ids.add(offer.id())) throw new IllegalArgumentException("Foreign or duplicate recovery candidate");
        }
        public Offer winner(double maximumHealth) {
            // Validate every candidate before picking a winner; invalid units/overflow never disappear behind ordering.
            var rates = new HashMap<String, Double>();
            candidates.forEach(o -> rates.put(o.id(), o.basis().resolve(o.perSecond(), maximumHealth)));
            return candidates.stream().min(Comparator.comparingInt(Offer::priority).reversed()
                    .thenComparing(Comparator.<Offer>comparingDouble(o -> rates.get(o.id())).reversed()).thenComparing(Offer::id)).orElseThrow();
        }
        public HealingCommand command(Offer winner) {
            if (!candidates.contains(winner)) throw new IllegalArgumentException("Foreign recovery winner");
            return healing(winner, from, until);
        }
    }
    public record Selected(Offer offer, double maximumHealth, HealingReceipt receipt) {
        public Selected { Objects.requireNonNull(offer); Objects.requireNonNull(receipt); Numbers.nonnegative(maximumHealth, "maximum health"); }
    }
    public record SelectionReceipt(Selection command, HealingReceipt.Outcome outcome, Optional<Selected> selected) implements RuleEngine.ActionResult {
        public SelectionReceipt {
            Objects.requireNonNull(command); Objects.requireNonNull(outcome); Objects.requireNonNull(selected);
            if (selected.isEmpty() && outcome != HealingReceipt.Outcome.MISSING && outcome != HealingReceipt.Outcome.DEAD) throw new IllegalArgumentException("Available recovery selection needs evidence");
            selected.ifPresent(value -> {
                if (!command.winner(value.maximumHealth()).equals(value.offer()) || !command.command(value.offer()).equals(value.receipt().command())
                        || value.receipt().outcome() != outcome || value.receipt().maximumHealth().isEmpty()
                        || Double.compare(value.maximumHealth(), value.receipt().maximumHealth().getAsDouble()) != 0) throw new IllegalArgumentException("Recovery selection receipt disagrees with its evidence");
            });
        }
    }
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
            group.sort(order); var first = group.getFirst();
            var eligible = group.stream().filter(o -> o.priority() == first.priority()).toList();
            if (eligible.stream().map(Offer::basis).distinct().count() > 1) {
                suppressed.addAll(group.stream().filter(o -> o.priority() < first.priority()).toList());
                var command = new Selection(first.target(), first.channel(), from, until, eligible);
                selected.add(new Allocation(eligible.stream().map(Offer::id).min(String::compareTo).orElseThrow(), from, until, command));
            } else {
                suppressed.addAll(group.subList(1, group.size()));
                selected.add(new Allocation(first.id(), from, until, healing(first, from, until)));
            }
        }
        return new Batch(selected, suppressed, following);
    }
    private static HealingCommand healing(Offer offer, long from, long until) {
        double amount = offer.perSecond() * ((until - from) / 1_000_000.0);
        var tags = new HashSet<>(offer.tags()); tags.add("chorus:continuous_healing");
        return new HealingCommand(offer.target(), offer.source(), amount, tags, offer.basis());
    }
    public static List<RuleEngine.EventRule<EffectState>> definitions() {
        var heal = new RuleEngine.Action<EffectState>() {
            @Override public RuleEngine.Outcome<EffectState> step(EffectState state, RuleEngine.Context context) {
                return new RuleEngine.Await<>(((Allocation) context.scope()).command());
            }
            @Override public RuleEngine.Local<EffectState> complete(EffectState state, RuleEngine.Context context, RuleEngine.ActionResult result) {
                var command = ((Allocation) context.scope()).command();
                if (result instanceof SelectionReceipt selection) {
                    if (!selection.command().equals(command)) throw new IllegalArgumentException("Recovery selection differs from its allocated interval");
                    return new RuleEngine.Local<>(state, selection, selection.selected().map(s -> HealingFacts.from(s.receipt())).orElse(List.of()));
                }
                var receipt = (HealingReceipt) result;
                if (!receipt.command().equals(command)) throw new IllegalArgumentException("Recovery receipt differs from its allocated interval");
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
        for (var allocation : batch.allocations()) bindings.add(new RuleEngine.RuleBinding(EVENT + "/" + allocation.id(), EVENT, allocation));
        bindings.add(new RuleEngine.RuleBinding(RELEASE, RELEASE, batch)); return List.copyOf(bindings);
    }
}
