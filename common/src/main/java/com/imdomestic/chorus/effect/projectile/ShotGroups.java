package com.imdomestic.chorus.effect.projectile;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;

/** One declared shot, receipt-derived progress, and one final summary. No mutable world references. */
public final class ShotGroups {
    public static final String DUE = "chorus:internal/shot_due", RESOLVED = "chorus:shot_resolved", PROGRESS = "chorus:shot_progress";
    private ShotGroups() {}
    public record Handle(String id, BuffInstance.Origin origin, int pellets, long startedAt, long dueAt)
            implements RuleEngine.ActionResult, RuleEngine.Payload {
        public Handle {
            Objects.requireNonNull(id); Objects.requireNonNull(origin);
            if (id.isBlank() || pellets < 1 || startedAt < 0 || dueAt <= startedAt || dueAt == Long.MAX_VALUE)
                throw new IllegalArgumentException("Invalid shot identity, count or lifetime");
        }
        public String timerId() { return id + "/expiry"; }
    }
    public record Member(Handle shot, int pellet) {
        public Member { Objects.requireNonNull(shot); if (pellet < 0 || pellet >= shot.pellets()) throw new IllegalArgumentException("Pellet index outside shot"); }
    }
    public enum Phase { RESERVED, FLYING, CLOSED, REJECTED }
    public record Pellet(Phase phase, long sequence, boolean contactOpen, Set<String> hits, Set<String> effective) {
        public Pellet {
            Objects.requireNonNull(phase); hits = Set.copyOf(hits); effective = Set.copyOf(effective);
            if (sequence < 0 || !hits.containsAll(effective) || hits.stream().anyMatch(String::isBlank)
                    || contactOpen && (phase != Phase.FLYING || sequence == 0)
                    || (phase == Phase.RESERVED || phase == Phase.REJECTED) && (sequence != 0 || !hits.isEmpty()))
                throw new IllegalArgumentException("Contradictory pellet progress");
        }
        public boolean closed() { return phase == Phase.CLOSED || phase == Phase.REJECTED; }
    }
    public record Group(Handle handle, Map<Integer, Pellet> pellets) {
        public Group {
            Objects.requireNonNull(handle); pellets = Collections.unmodifiableMap(new TreeMap<>(pellets));
            if (pellets.keySet().stream().anyMatch(i -> i < 0 || i >= handle.pellets())) throw new IllegalArgumentException("Pellet outside group");
        }
        Group with(int index, Pellet pellet) { var copy = new TreeMap<>(pellets); copy.put(index, pellet); return new Group(handle, copy); }
        public boolean complete() { return pellets.size() == handle.pellets() && pellets.values().stream().allMatch(Pellet::closed); }
    }
    /** Per-target values count unique pellets, including piercing pellets only once for each target. */
    public record Summary(EffectEvent event, Handle shot, boolean complete, Map<String, Integer> hits,
            Map<String, Integer> effective) implements EffectEvent.Carrier {
        public Summary { hits = Collections.unmodifiableMap(new TreeMap<>(hits)); effective = Collections.unmodifiableMap(new TreeMap<>(effective)); }
    }
    public record Progress(EffectEvent event, Summary before, Summary after) implements EffectEvent.Carrier {}
    /** A threshold can be reached before every pellet has ended. Only newly confirmed counts publish progress. */
    public static List<RuleEngine.Signal> progress(EffectState before, EffectState after, Member member) {
        var previous = summarize(group(before, member).orElseThrow()); var current = summarize(group(after, member).orElseThrow());
        if (previous.hits().equals(current.hits()) && previous.effective().equals(current.effective())) return List.of();
        var event = current.event(); var numbers = new TreeMap<>(event.numbers());
        for (String field : List.of("pellets_hit", "pellets_effective", "max_pellets_on_target", "max_effective_pellets_on_target"))
            numbers.put("previous_" + field, previous.event().numbers().get(field));
        var refs = new TreeMap<>(event.references()); refs.put("pellet", Integer.toString(member.pellet()));
        return List.of(new RuleEngine.Signal(PROGRESS, new Progress(new EffectEvent(event.actor(), event.victim(), event.source(), event.tags(), numbers, event.flags(), refs), previous, current)));
    }
    public static RuleEngine.Local<EffectState> begin(EffectState state, Handle handle) {
        if (handle.startedAt() != state.buffs().timeMicros() || state.shotGroups().containsKey(handle.id())) throw new IllegalArgumentException("Shot already exists or starts at another time");
        var timer = new EffectState.Timer(handle.timerId(), handle.dueAt(), 0, 1, new RuleEngine.Signal(DUE, handle), Optional.empty());
        return new RuleEngine.Local<>(state.withShotGroup(new Group(handle, Map.of())).schedule(timer), handle, List.of());
    }
    private static Optional<Group> group(EffectState state, Member member) {
        var group = state.shotGroups().get(member.shot().id());
        if (group == null) return Optional.empty();
        if (!group.handle().equals(member.shot())) throw new IllegalArgumentException("Shot handle mismatch");
        return Optional.of(group);
    }
    public static boolean active(EffectState state, Member member) {
        return group(state, member).filter(g -> state.buffs().timeMicros() < g.handle().dueAt()).isPresent();
    }
    public static EffectState reserve(EffectState state, Member member) {
        var group = group(state, member);
        if (!active(state, member)) return state; // Same-boundary timers may run before the expiry fact is consumed.
        var value = group.orElseThrow();
        if (value.pellets().containsKey(member.pellet())) throw new IllegalArgumentException("Pellet index already launched");
        return state.withShotGroup(value.with(member.pellet(), new Pellet(Phase.RESERVED, 0, false, Set.of(), Set.of())));
    }
    public static RuleEngine.Local<EffectState> launched(EffectState state, ProjectileFlight.Receipt receipt) {
        var member = receipt.launch().member().orElseThrow(); var group = group(state, member);
        if (!active(state, member)) {
            if (receipt.outcome() != ProjectileFlight.Outcome.EXPIRED_SHOT) throw new IllegalArgumentException("Expired shot cannot launch");
            return new RuleEngine.Local<>(state, receipt, List.of());
        }
        if (receipt.outcome() == ProjectileFlight.Outcome.EXPIRED_SHOT) throw new IllegalArgumentException("Active shot reported expired launch");
        var value = group.orElseThrow(); var pellet = value.pellets().get(member.pellet());
        if (pellet == null || pellet.phase() != Phase.RESERVED) throw new IllegalArgumentException("Unreserved pellet launch");
        var next = value.with(member.pellet(), new Pellet(receipt.outcome() == ProjectileFlight.Outcome.LAUNCHED ? Phase.FLYING : Phase.REJECTED, 0, false, Set.of(), Set.of()));
        return settle(state.withShotGroup(next), next, receipt, false);
    }
    public static boolean accepts(EffectState state, ProjectileFlight.Impact impact) {
        var member = impact.member().orElseThrow(); var group = group(state, member);
        if (group.isEmpty() || !active(state, member)) return false;
        var pellet = group.orElseThrow().pellets().get(member.pellet());
        if (pellet == null || pellet.phase() != Phase.FLYING || impact.sequence() <= pellet.sequence()) return false;
        if (pellet.contactOpen() || impact.sequence() != pellet.sequence() + 1) throw new IllegalArgumentException("Out-of-order pellet contact");
        return true;
    }
    public static EffectState open(EffectState state, ProjectileFlight.Impact impact) {
        if (!accepts(state, impact)) throw new IllegalArgumentException("Inactive pellet contact");
        var member = impact.member().orElseThrow(); var group = group(state, member).orElseThrow(); var pellet = group.pellets().get(member.pellet());
        return state.withShotGroup(group.with(member.pellet(), new Pellet(Phase.FLYING, impact.sequence(), true, pellet.hits(), pellet.effective())));
    }
    private static Pellet contact(EffectState state, ProjectileFlight.Impact impact) {
        var member = impact.member().orElseThrow(); var group = group(state, member).orElseThrow(() -> new IllegalArgumentException("Expired pellet contact"));
        var pellet = group.pellets().get(member.pellet());
        if (pellet == null || !pellet.contactOpen() || pellet.sequence() != impact.sequence()) throw new IllegalArgumentException("Pellet contact is no longer open");
        return pellet;
    }
    public static void validateDamage(EffectState state, ProjectileFlight.Impact impact, DamageCommand command) {
        contact(state, impact);
        var origin = impact.member().orElseThrow().shot().origin(); var source = command.source();
        if (!impact.target().orElseThrow(() -> new IllegalArgumentException("Pellet damage needs an entity contact")).equals(command.target())
                || !origin.owner().equals(source.owner()) || !origin.weapon().equals(source.weapon())
                || !origin.source().equals(source.source()) || !origin.ability().equals(source.ability()))
            throw new IllegalArgumentException("Damage does not belong to this pellet contact");
    }
    public static EffectState damage(EffectState state, ProjectileFlight.Impact impact, DamageCommand command, DamageReceipt receipt) {
        validateDamage(state, impact, command);
        if (receipt.outcome() == DamageReceipt.Outcome.CANCELLED || receipt.outcome() == DamageReceipt.Outcome.FAILED) return state;
        var member = impact.member().orElseThrow(); var group = group(state, member).orElseThrow(); var pellet = contact(state, impact);
        var hits = new HashSet<>(pellet.hits()); hits.add(command.target());
        var effective = new HashSet<>(pellet.effective()); if (receipt.effective(true) > 0) effective.add(command.target());
        return state.withShotGroup(group.with(member.pellet(), new Pellet(pellet.phase(), pellet.sequence(), true, hits, effective)));
    }
    public static RuleEngine.Local<EffectState> close(EffectState state, ProjectileFlight.Impact impact) {
        var member = impact.member().orElseThrow(); var group = group(state, member).orElseThrow(); var pellet = contact(state, impact);
        var next = group.with(member.pellet(), new Pellet(impact.terminal() ? Phase.CLOSED : Phase.FLYING, pellet.sequence(), false, pellet.hits(), pellet.effective()));
        return settle(state.withShotGroup(next), next, RuleEngine.Empty.INSTANCE, false);
    }
    public static RuleEngine.Local<EffectState> expire(EffectState state, Handle handle) {
        var group = state.shotGroups().get(handle.id());
        if (group == null) return new RuleEngine.Local<>(state, RuleEngine.Empty.INSTANCE, List.of());
        if (!group.handle().equals(handle) || state.buffs().timeMicros() != handle.dueAt()) throw new IllegalArgumentException("Wrong shot deadline");
        return settle(state, group, RuleEngine.Empty.INSTANCE, true);
    }
    private static RuleEngine.Local<EffectState> settle(EffectState state, Group group, RuleEngine.ActionResult result, boolean expired) {
        if (!expired && !group.complete()) return new RuleEngine.Local<>(state, result, List.of());
        return new RuleEngine.Local<>(state.withoutShotGroup(group.handle().id()).cancel(group.handle().timerId()), result,
                List.of(new RuleEngine.Signal(RESOLVED, summarize(group))));
    }
    private static Summary summarize(Group group) {
        var hits = new TreeMap<String, Integer>(); var effective = new TreeMap<String, Integer>();
        int hit = 0, dealt = 0, rejected = 0, missed = 0, closed = 0;
        for (var pellet : group.pellets().values()) {
            pellet.hits().forEach(id -> hits.merge(id, 1, Integer::sum)); pellet.effective().forEach(id -> effective.merge(id, 1, Integer::sum));
            if (!pellet.hits().isEmpty()) hit++;
            if (!pellet.effective().isEmpty()) dealt++;
            if (pellet.closed()) closed++;
            if (pellet.phase() == Phase.REJECTED) rejected++;
            if (pellet.phase() == Phase.CLOSED && pellet.hits().isEmpty()) missed++;
        }
        var numbers = new TreeMap<String, Measure>();
        numbers.put("pellets_total", count(group.handle().pellets())); numbers.put("pellets_hit", count(hit)); numbers.put("pellets_effective", count(dealt));
        numbers.put("pellets_rejected", count(rejected)); numbers.put("pellets_missed", count(missed)); numbers.put("pellets_unresolved", count(group.handle().pellets() - closed));
        numbers.put("targets_hit", count(hits.size())); numbers.put("max_pellets_on_target", count(hits.values().stream().mapToInt(i -> i).max().orElse(0)));
        numbers.put("max_effective_pellets_on_target", count(effective.values().stream().mapToInt(i -> i).max().orElse(0)));
        // TreeMap order gives stable target identity for ties. The full per-target maps remain in the typed payload.
        String victim = ""; int maximum = 0;
        for (var entry : hits.entrySet()) if (entry.getValue() > maximum) { maximum = entry.getValue(); victim = entry.getKey(); }
        var handle = group.handle(); var event = new EffectEvent(handle.origin().owner(), victim, handle.origin(), handle.origin().tags(), numbers,
                Map.of("complete", group.complete(), "all_hit", group.complete() && hit == handle.pellets()), Map.of("shot", handle.id()));
        return new Summary(event, handle, group.complete(), hits, effective);
    }
    private static Measure count(int value) { return new Measure(value, Unit.COUNT); }
}
