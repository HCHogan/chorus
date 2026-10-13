package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.effect.object.PickupCollection;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.imdomestic.chorus.stat.codec.StatCodecs;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual accepted abilities and shared status application, with explicitly synthetic timing inputs. */
class WintersShroudTest {
    static final String D = "chorus_d2:";
    static CompiledEffects program() throws Exception {
        var parts = new ArrayList<EffectProgram>();
        for (String name : List.of("winters_shroud", "class_regeneration", "marksman_dodge", "gamblers_dodge", "ascension",
                "character_stats", "amplified", "amplified_inputs", "weapon_stats", "jolt", "freeze", "freeze_test_falloff",
                "slow", "stasis_duration", "durance", "combat_damage", "movement_attributes")) {
            var data = json(name); AmplifiedTest.version(data);
            if (List.of("marksman_dodge", "gamblers_dodge", "ascension").contains(name))
                json(name + "_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e -> data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value", e.getValue()));
            parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
        }
        for (String name : List.of("ascension", "gamblers_dodge", "winters_shroud")) {
            var data = json(name + "_test_calibration").getAsJsonObject("program");
            if (name.equals("ascension")) data.remove("profiles");
            parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
        }
        return CompiledEffects.link(parts);
    }
    static EffectSource aspect(String id, String holder, String variant) throws Exception {
        var data = json("winters_shroud_test_calibration"); var params = new HashMap<String, Measure>();
        var values = data.getAsJsonObject("parameters").deepCopy();
        if (variant.equals("stasis")) data.getAsJsonObject("stasis_parameters").entrySet().forEach(e -> values.add(e.getKey(), e.getValue()));
        values.entrySet().forEach(e -> params.put(e.getKey(), StatCodecs.MEASURE.parse(JsonOps.INSTANCE, e.getValue()).getOrThrow()));
        return new EffectSource(id, D + "winters_shroud_" + variant, holder, new BuffInstance.Origin(holder, id, "", ""), Set.of(), params);
    }
    static final class Harness {
        final CompiledEffects p; EffectSession session; int sequence; boolean denied, failStatus, missingTargets, dead;
        Optional<Boolean> allied = Optional.of(false); List<String> targets = List.of("enemy", "guardian");
        final List<StatusResult.Check> checks = new ArrayList<>(); final List<TargetQuery> queries = new ArrayList<>();
        final List<Displacement.Command> moves = new ArrayList<>(); final List<DamageCommand> hits = new ArrayList<>();
        Harness(String variant, EffectState.Mode mode) throws Exception {
            p = program(); session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty().withMode(mode), this::world);
            choose("class", "marksman_dodge"); choose("air_move", "ascension"); choose("melee", "test:ascension_melee"); bind(aspect("aspect", "player", variant));
        }
        Harness() throws Exception { this("stasis", EffectState.Mode.PVE); }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            return switch (request.command()) {
                case EntityQuery q -> new EntityQuery.Result(q, Optional.of(dead && q.target().equals("player") ? new EntityQuery.View(false, true, 0, 1000, 0) : FreezeTest.view(q.target().equals("player") || q.target().equals("guardian"), D + "elite")));
                case RelationQuery q -> new RelationQuery.Result(q, allied);
                case TargetQuery q -> { queries.add(q); yield new TargetQuery.Result(q, missingTargets ? TargetQuery.Outcome.MISSING_CENTER : TargetQuery.Outcome.AVAILABLE, missingTargets || q.relation() == TargetQuery.Relation.ALLIED ? List.of() : targets.stream().filter(t -> !q.exclude().contains(t) && (q.includeCenter() || !(q.center() instanceof TargetQuery.EntityCenter center) || !center.entity().equals(t))).map(t -> new TargetQuery.Target(t, 4)).sorted(q.comparator()).toList()); }
                case StatusResult.Check q -> { checks.add(q); if (failStatus) throw new IllegalStateException("Unknown status authorization"); yield new StatusResult.Checked(q, denied ? StatusResult.Decision.DENIED : StatusResult.Decision.ALLOWED); }
                case PickupCollection.Query q -> new PickupCollection.Result(q, PickupCollection.Outcome.FOUND, List.of());
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(new WorldPosition("world", 0, 40, 0)));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 1, 0, 0)));
                case Displacement.Command c -> { moves.add(c); yield new Displacement.Receipt(c, Displacement.Outcome.APPLIED, Optional.of(new Displacement.Change(new WorldPosition("world", 0, 40, 0), new WorldPosition("world", 0, 43, 0), new Displacement.Offset(0, 3, 0)))); }
                case DamageCommand c -> { hits.add(c); yield new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, c.amount(), Optional.empty(), false); }
                default -> throw new AssertionError(request.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void send(RuleEngine.Signal s) { session.start(now(), s); }
        void bind(EffectSource s) { send(SourceChange.bind(s)); }
        void choose(String slot, String ability) { var old = state().abilities().getOrDefault("player", AbilityLoadout.EMPTY); var slots = new HashMap<>(old.slots()); if (ability == null) slots.remove(D + slot); else slots.put(D + slot, ability.contains(":") ? ability : D + ability); send(new AbilityChange("player", old, new AbilityLoadout(slots)).signal()); }
        AbilityUse.Receipt cast(String slot, boolean ground) { var q = new AbilityUse.Request("player", D + slot, "cast/" + ++sequence, new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of("chorus:class_ability"), Map.of(), Map.of("on_ground", ground), Map.of())); var r = (AbilityUse.Receipt) p.useAbility(state(), q).result(); send(q.signal()); return r; }
        void slow(String sourceId, String holder, String victim, int stacks) { var s = SlowTest.source(sourceId, holder, 2); bind(s); send(new RuleEngine.Signal(D + "apply_slow", new EffectEvent(holder, victim, s.origin(), Set.of(), Map.of("stacks", new Measure(stacks, Unit.COUNT)), Map.of(), Map.of("source_instance", sourceId, "bundle", s.bundle())))); }
        void until(long micros) { session.observe(micros, List.of()); }
        double energy(String ability) { return state().resources().get(new ResourceState.Key("player", D + ability + "_energy")).value(); }
        Optional<BuffInstance> buff(String name, String holder) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(D + name) && b.key().holder().equals(holder)).findFirst(); }
        double defense(Set<String> tags) { return p.defense(state(), new DamageCommand("player", new BuffInstance.Origin("attacker", "shot", "", "", tags), 10, "minecraft:generic", Set.of(), Set.of(), false), 10).orElseThrow().output().value(); }
        void restore() { session = EffectSession.restore(p, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())), this::world); }
    }
    @Test void acceptedClassCastAppliesTargetSpecificSlowAndDeduplicatesAspectCopies() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = new Harness("stasis", mode); h.bind(aspect("duplicate", "player", "stasis")); h.bind(DuranceTest.fragment("fragment", "player"));
            assertEquals(AbilityUse.Outcome.ACCEPTED, h.cast("class", false).outcome()); assertEquals(0, h.energy("marksman_dodge"));
            assertEquals(2, h.checks.size()); assertEquals(60, h.buff("slow", "enemy").orElseThrow().count()); assertEquals(40, h.buff("slow", "guardian").orElseThrow().count());
            assertEquals(12_000_000, h.buff("slow", "enemy").orElseThrow().deadline()); assertEquals(2_000_000, h.buff("slow", "guardian").orElseThrow().deadline());
            assertEquals(mode == EffectState.Mode.PVE ? 9 : 8, h.queries.getFirst().radius()); assertEquals(D + "marksman_dodge", h.buff("slow", "enemy").orElseThrow().origin().ability());
            assertEquals(3_000_000, h.buff("winters_shroud_regeneration", "player").orElseThrow().deadline());
            assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.cast("class", false).outcome()); assertEquals(2, h.checks.size());
        }
    }
    @Test void prismaticHasItsOwnDurationsEvenWhenHostIncorrectlyAlsoBindsDurance() throws Exception {
        var h = new Harness("prismatic", EffectState.Mode.PVE); h.bind(DuranceTest.fragment("fragment", "player")); h.cast("class", false);
        assertEquals(8_000_000, h.buff("slow", "enemy").orElseThrow().deadline()); assertEquals(1_500_000, h.buff("slow", "guardian").orElseThrow().deadline());
    }
    @Test void deniedMissingAndNonClassActionsCannotInventSuccessfulSlowRegeneration() throws Exception {
        for (boolean denial : new boolean[] {true, false}) { var h = new Harness(); h.denied = denial; h.missingTargets = !denial; h.cast("class", false); assertTrue(h.buff("winters_shroud_regeneration", "player").isEmpty()); assertTrue(h.buff("winters_shroud_resistance", "player").isPresent()); }
        var h = new Harness(); h.cast("melee", false); assertTrue(h.checks.isEmpty()); assertTrue(h.buff("winters_shroud_resistance", "player").isEmpty());
        assertEquals(AbilityUse.Outcome.CONDITION, h.cast("air_move", true).outcome()); assertTrue(h.checks.isEmpty());
    }
    @Test void everySuccessfulEnemySlowProducerQualifiesUsingLatestActorNotRetainedStatusOwner() throws Exception {
        var h = new Harness(); h.slow("first", "other", "enemy", 1); assertTrue(h.buff("winters_shroud_regeneration", "player").isEmpty());
        h.slow("second", "player", "enemy", 1); assertEquals("other", h.buff("slow", "enemy").orElseThrow().origin().owner()); assertTrue(h.buff("winters_shroud_regeneration", "player").isPresent());
        for (var relation : List.of(Optional.of(true), Optional.<Boolean>empty())) { var rejected = new Harness(); rejected.allied = relation; rejected.slow("source", "player", "enemy", 1); assertTrue(rejected.buff("winters_shroud_regeneration", "player").isEmpty()); }
    }
    @Test void basePercentageIsIndependentOfStatFactorAndStopsAtTheExactDeadline() throws Exception {
        for (var mode : EffectState.Mode.values()) { var h = new Harness("stasis", mode); h.bind(new EffectSource("rate", "test:winters_shroud_stat", "player", new BuffInstance.Origin("player", "rate", "", ""), Set.of())); h.cast("class", false); h.until(3_000_000); double rate = mode == EffectState.Mode.PVE ? 5 : 3.5; assertEquals(3 * rate / 42, h.energy("marksman_dodge"), 1e-10); assertTrue(h.buff("winters_shroud_regeneration", "player").isEmpty()); h.until(4_000_000); assertEquals((3 * rate + 2) / 42, h.energy("marksman_dodge"), 1e-10); }
    }
    @Test void regenerationFollowsSelectionAndClearLeavesOnlyExistingBaselinePolicies() throws Exception {
        var h = new Harness(); h.cast("class", false); h.until(1_000_000); assertEquals(4d / 42, h.energy("marksman_dodge"), 1e-10);
        h.choose("class", "gamblers_dodge"); h.targets = List.of(); assertEquals(AbilityUse.Outcome.ACCEPTED, h.cast("class", true).outcome()); h.until(2_000_000);
        assertEquals(5d / 42, h.energy("marksman_dodge"), 1e-10); assertEquals(4d / 56, h.energy("gamblers_dodge"), 1e-10);
        h.choose("class", null); h.until(3_000_000); assertEquals(6d / 42, h.energy("marksman_dodge"), 1e-10); assertEquals(4d / 56, h.energy("gamblers_dodge"), 1e-10);
    }
    @Test void refreshUsesSuccessfulStatusIdentityAndSharedHundredStackFreezeConversion() throws Exception {
        var h = new Harness(); h.cast("class", false); h.until(1_000_000); h.slow("blade", "player", "enemy", 40);
        assertTrue(h.buff("slow", "enemy").isEmpty()); assertTrue(h.buff("freeze", "enemy").isPresent()); assertEquals(4_000_000, h.buff("winters_shroud_regeneration", "player").orElseThrow().deadline());
        var receipt = h.buff("winters_shroud_receipts", "player").orElseThrow(); String seen = receipt.components().sets().get("statuses").iterator().next(); h.until(2_000_000);
        h.send(new RuleEngine.Signal("chorus:status_applied", new EffectEvent("player", "enemy", new BuffInstance.Origin("player", "duplicate", "", ""), Set.of(), Map.of(), Map.of("applied", true), Map.of("buff_definition", D + "slow", "status_id", seen))));
        assertEquals(4_000_000, h.buff("winters_shroud_regeneration", "player").orElseThrow().deadline());
    }
    @Test void combatantDefenseExpiresAndDeathClearsBothBenefitsButDetachingPreservesGrantedDuration() throws Exception {
        for (boolean death : new boolean[] {true, false}) { var h = new Harness(); h.cast("class", false); assertEquals(5, h.defense(Set.of("chorus:combatant"))); for (var tags : List.of(Set.<String>of(), Set.of("chorus:guardian"), Set.of("chorus:guardian", "chorus:combatant"))) assertEquals(10, h.defense(tags));
            h.send(SourceChange.remove("aspect")); assertTrue(h.buff("winters_shroud_regeneration", "player").isPresent());
            if (death) h.send(new RuleEngine.Signal("chorus:death", new EffectEvent("enemy", "player", new BuffInstance.Origin("enemy", "attack", "", ""), Set.of(), Map.of()))); else h.until(4_000_000);
            assertTrue(h.buff("winters_shroud_regeneration", "player").isEmpty()); assertTrue(h.buff("winters_shroud_resistance", "player").isEmpty()); assertEquals(10, h.defense(Set.of("chorus:combatant")));
        }
    }
    @Test void actualAscensionPaysDodgeOnceAndComposesSlowWithJoltAndAmplified() throws Exception {
        var h = new Harness("prismatic", EffectState.Mode.PVE); h.cast("air_move", false); assertEquals(1, h.moves.size()); assertEquals(0, h.energy("marksman_dodge"));
        for (String enemy : List.of("enemy", "guardian")) { assertTrue(h.buff("slow", enemy).isPresent()); assertTrue(h.buff("jolt", enemy).isPresent()); assertEquals(D + "ascension", h.buff("slow", enemy).orElseThrow().origin().ability()); }
        assertTrue(h.buff("amplified", "player").isPresent()); assertTrue(h.buff("winters_shroud_regeneration", "player").isPresent());
    }
    @Test void unknownAuthorizationRetainsPaymentAndPriorDefenseWithoutReplaying() throws Exception {
        var h = new Harness(); h.failStatus = true; assertThrows(IllegalStateException.class, () -> h.cast("class", false)); assertEquals(0, h.energy("marksman_dodge")); assertTrue(h.buff("winters_shroud_resistance", "player").isPresent()); assertTrue(h.buff("winters_shroud_regeneration", "player").isEmpty()); int checks = h.checks.size(); assertThrows(IllegalStateException.class, () -> h.until(1_000_000)); assertEquals(checks, h.checks.size());
    }
    @Test void restoreRetainsTimersAndCalibrationIsRequiredRatherThanAssumed() throws Exception {
        var h = new Harness(); var source = aspect("invalid", "player", "stasis"); var missing = new HashMap<>(source.parameters()); missing.remove("resist_duration"); assertThrows(IllegalArgumentException.class, () -> h.p.validateSource(new EffectSource(source.instance(), source.bundle(), source.holder(), source.origin(), Set.of(), missing)));
        h.cast("class", false); h.until(1_000_000); h.restore(); h.until(4_000_000); assertEquals(13d / 42, h.energy("marksman_dodge"), 1e-10); assertTrue(h.buff("winters_shroud_resistance", "player").isEmpty()); h.until(60_000_000); assertTrue(h.buff("winters_shroud_receipts", "player").isEmpty());
        assertEquals(h.p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.p.program()).getOrThrow()).getOrThrow());
    }
}
