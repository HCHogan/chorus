package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.object.PickupCollection;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AscensionTest {
    static final String D = "chorus_d2:", ABILITY = D + "ascension", SLOT = D + "air_move";
    static CompiledEffects program(boolean calibrated) throws Exception {
        var parts = new ArrayList<EffectProgram>();
        for (String name : List.of("ascension", "marksman_dodge", "gamblers_dodge", "character_stats", "amplified", "amplified_inputs", "weapon_stats", "jolt")) {
            var d = json(name); AmplifiedTest.version(d);
            if (List.of("ascension", "marksman_dodge", "gamblers_dodge").contains(name) && (!name.equals("ascension") || calibrated))
                json(name + "_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e -> d.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value", e.getValue()));
            parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, d).getOrThrow());
        }
        for (String name : List.of("ascension", "gamblers_dodge")) parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json(name + "_test_calibration").get("program")).getOrThrow());
        return CompiledEffects.link(parts);
    }
    static final class Harness {
        final CompiledEffects p; EffectSession session; int sequence; boolean failMove, failDamage, queryMissing;
        Displacement.Outcome motion = Displacement.Outcome.APPLIED;
        final List<Displacement.Command> moves = new ArrayList<>(); final List<DamageCommand> damage = new ArrayList<>(); final List<Double> amounts = new ArrayList<>();
        final List<TargetQuery> queries = new ArrayList<>(); final List<StatusResult.Check> statuses = new ArrayList<>();
        final Set<String> deny = new HashSet<>(); boolean guardian = true;
        Harness() throws Exception { this(true); }
        Harness(boolean calibrated) throws Exception {
            p = program(calibrated); session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty(), this::world);
            choose(D + "class", D + "marksman_dodge"); choose(SLOT, ABILITY); choose(D + "melee", "test:ascension_melee");
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            return switch (r.command()) {
                case PickupCollection.Query q -> new PickupCollection.Result(q, PickupCollection.Outcome.FOUND, List.of());
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(new WorldPosition("world", 0, 40, 0)));
                case Displacement.Command c -> {
                    moves.add(c); if (failMove) throw new IllegalStateException("Unknown ascent");
                    double dy = motion == Displacement.Outcome.APPLIED ? c.distance() : 0;
                    yield motion == Displacement.Outcome.APPLIED || motion == Displacement.Outcome.UNCHANGED
                            ? new Displacement.Receipt(c, motion, Optional.of(new Displacement.Change(new WorldPosition("world", 0, 40, 0), new WorldPosition("world", 0, 40 + dy, 0), new Displacement.Offset(0, dy, 0)))) : Displacement.Receipt.rejected(c, motion);
                }
                case EntityQuery q -> new EntityQuery.Result(q, Optional.of(new EntityQuery.View(true, q.target().equals("player") || q.target().equals("ally") || q.target().equals("guardian"), 1000, 1000, 0)));
                case RelationQuery q -> new RelationQuery.Result(q, Optional.of(false));
                case TargetQuery q -> {
                    queries.add(q); var targets = q.radius() == 10 ? (q.relation() == TargetQuery.Relation.ALLIED ? List.of("ally") : guardian ? List.of("enemy", "guardian") : List.of("enemy")) : q.radius() == 15 ? List.of("enemy") : List.<String>of();
                    yield new TargetQuery.Result(q, queryMissing ? TargetQuery.Outcome.MISSING_CENTER : TargetQuery.Outcome.AVAILABLE,
                            queryMissing ? List.of() : targets.stream().map(t -> new TargetQuery.Target(t, 4)).sorted(q.comparator()).toList());
                }
                case StatusResult.Check q -> { statuses.add(q); yield new StatusResult.Checked(q, deny.contains(q.target()) ? StatusResult.Decision.DENIED : StatusResult.Decision.ALLOWED); }
                case DamageCommand c -> {
                    damage.add(c); if (failDamage) throw new IllegalStateException("Unknown Ascension damage");
                    double amount = p.outgoing(state(), c, c.amount()).map(x -> x.output().value()).orElse(c.amount()); amounts.add(amount);
                    yield new DamageReceipt(r.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, amount, Optional.empty(), false);
                }
                default -> throw new AssertionError(r.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        void send(RuleEngine.Signal s) { session.start(state().buffs().timeMicros(), s); }
        void choose(String slot, String ability) { var old = state().abilities().getOrDefault("player", AbilityLoadout.EMPTY); var slots = new HashMap<>(old.slots()); if (ability == null) slots.remove(slot); else slots.put(slot, ability); send(new AbilityChange("player", old, new AbilityLoadout(slots)).signal()); }
        AbilityUse.Request request(String slot, boolean ground) { return new AbilityUse.Request("player", slot, "cast/" + ++sequence, new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of(), Map.of("on_ground", ground), Map.of())); }
        AbilityUse.Receipt use(boolean ground) { var q = request(SLOT, ground); var receipt = (AbilityUse.Receipt) p.useAbility(state(), q).result(); send(q.signal()); return receipt; }
        double energy(String name) { return state().resources().get(new ResourceState.Key("player", name)).value(); }
        Optional<BuffInstance> buff(String name, String holder) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(D + name) && b.key().holder().equals(holder)).findFirst(); }
        void stat(String name, double points) { send(SourceChange.bind(new EffectSource("stat/" + name, name.equals("melee") ? "test:ascension_melee_stat" : "test:ascension_stat", "player", new BuffInstance.Origin("player", "stat/" + name, "", ""), Set.of(), Map.of("points", new Measure(points, Unit.STAT_POINT))))); }
    }
    @Test void unknownCalibrationFailsBeforePaymentAndAirborneConditionPrecedesIt() throws Exception {
        var h = new Harness(false); assertEquals(AbilityUse.Outcome.CONDITION, h.use(true).outcome());
        assertThrows(IllegalArgumentException.class, () -> h.p.useAbility(h.state(), h.request(SLOT, false)));
        assertEquals(1, h.energy(D + "marksman_dodge_energy")); assertTrue(h.moves.isEmpty());
    }
    @Test void acceptedAirActionPaysCurrentDodgeAndSeparatesAllyBuffsFromEnemyStatus() throws Exception {
        var h = new Harness(); var r = h.use(false); assertEquals(AbilityUse.Outcome.ACCEPTED, r.outcome()); assertEquals(0, h.energy(D + "marksman_dodge_energy"));
        assertEquals(1, h.moves.size()); assertEquals(1, h.moves.getFirst().direction().orElseThrow().y()); assertEquals(3, h.moves.getFirst().distance());
        assertEquals(List.of(18.1, 7.0, 11.9), h.amounts); assertTrue(h.damage.stream().allMatch(c -> c.source().ability().equals(ABILITY)));
        assertTrue(h.damage.subList(0, 2).stream().allMatch(c -> c.tags().contains("chorus:class_damage")));
        assertTrue(h.damage.getLast().tags().contains("chorus_d2:jolt_damage"), "shared Jolt counts the initial explosion once");
        assertEquals(15_000_000, h.buff("amplified", "player").orElseThrow().deadline()); assertTrue(h.buff("amplified", "ally").isPresent()); assertTrue(h.buff("amplified", "enemy").isEmpty());
        assertEquals(10_000_000, h.buff("jolt", "enemy").orElseThrow().deadline()); assertEquals(5_000_000, h.buff("jolt", "guardian").orElseThrow().deadline());
        assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.use(false).outcome()); assertEquals(1, h.moves.size());
    }
    @Test void classStatCalibrationUsesSelectedStatsAndDoesNotAlterGuardianDamage() throws Exception {
        for (int points : new int[] {0, 100, 150, 200}) {
            var h = new Harness(); h.stat("class", points); h.use(false);
            assertEquals(18.1 * (1 + Math.max(0, points - 100) * .0065), h.amounts.getFirst(), 1e-9); assertEquals(7, h.amounts.get(1));
        }
    }
    @Test void blockedMotionStillAppliesEffectsButKnownMissingMotionDoesNot() throws Exception {
        for (var outcome : List.of(Displacement.Outcome.UNCHANGED, Displacement.Outcome.UNLOADED)) {
            var h = new Harness(); h.motion = outcome; h.use(false); assertEquals(0, h.energy(D + "marksman_dodge_energy"));
            assertEquals(outcome == Displacement.Outcome.UNCHANGED ? 3 : 0, h.damage.size()); assertEquals(outcome == Displacement.Outcome.UNCHANGED, h.buff("amplified", "player").isPresent());
        }
    }
    @Test void deniedJoltDoesNotCancelDamageOrOtherTargetsAndMissingQueryStillBuffsSelf() throws Exception {
        var h = new Harness(); h.deny.add("enemy"); h.use(false); assertEquals(2, h.damage.size()); assertTrue(h.buff("jolt", "enemy").isEmpty()); assertTrue(h.buff("jolt", "guardian").isPresent());
        var missing = new Harness(); missing.queryMissing = true; missing.use(false); assertTrue(missing.damage.isEmpty()); assertTrue(missing.buff("amplified", "player").isPresent());
    }
    @Test void removingDodgeDoesNotBorrowItsRetainedBalance() throws Exception {
        var h = new Harness(); h.choose(D + "class", null); assertEquals(AbilityUse.Outcome.CONDITION, h.use(false).outcome()); assertEquals(1, h.energy(D + "marksman_dodge_energy")); assertTrue(h.moves.isEmpty());
    }
    @Test void gamblersBenefitReturnsMeleeAfterAirAndDodgeSelectionAreClearedWithoutAnotherDisplacement() throws Exception {
        var h = new Harness(); h.choose(D + "class", D + "gamblers_dodge"); h.stat("melee", 100);
        h.send(h.request(D + "melee", false).signal()); h.send(h.request(D + "melee", false).signal()); assertEquals(0, h.energy("test:ascension_melee_energy"));
        h.use(false); assertEquals(0, h.energy(D + "gamblers_dodge_energy")); assertEquals(1, h.energy(D + "marksman_dodge_energy"));
        h.choose(D + "class", null); h.choose(SLOT, null); h.session.observe(199_999, List.of()); assertEquals(0, h.energy("test:ascension_melee_energy"));
        h.session.observe(200_000, List.of()); assertEquals(2, h.energy("test:ascension_melee_energy")); assertEquals(1, h.moves.size());
        assertTrue(h.buff("gamblers_dodge_tracking", "player").isEmpty(), "Ascension must not replay the dodge animation benefits");
    }
    @Test void unknownMotionOrDamageNeverReplaysOrReturnsPaidClassEnergy() throws Exception {
        for (boolean motion : new boolean[] {true, false}) {
            var h = new Harness(); h.failMove = motion; h.failDamage = !motion; assertThrows(IllegalStateException.class, () -> h.use(false));
            assertEquals(0, h.energy(D + "marksman_dodge_energy")); assertThrows(IllegalStateException.class, () -> h.session.observe(300_000, List.of())); assertEquals(1, h.moves.size()); assertEquals(motion ? 0 : 1, h.damage.size());
        }
    }
    @Test void acceptedDelayedBenefitAndBuffsSurviveDomainRestoreAfterBothSlotsAreCleared() throws Exception {
        var h = new Harness(); h.use(false); h.choose(D + "class", null); h.choose(SLOT, null);
        h.session = EffectSession.restore(h.p, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(h.session.checkpoint())), h::world);
        h.session.observe(200_000, List.of()); assertEquals(3, h.damage.size());
        assertEquals(2, h.state().timers().size());
        assertTrue(h.state().timers().values().stream().allMatch(t -> t.signal().type().equals(D + "amplified_sample")), "only the two ongoing Amplified samples may remain");
        assertTrue(h.buff("amplified", "player").isPresent());
        var p = h.p.program(); assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
    }
}
