package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.*;
import com.imdomestic.chorus.effect.object.PickupCollection;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AcrobatDodgeTest {
    static final String D = "chorus_d2:", ABILITY = D + "acrobat_dodge", ENERGY = D + "acrobat_dodge_energy";
    static CompiledEffects program(boolean calibrated) throws Exception { return program(calibrated, false); }
    static CompiledEffects program(boolean calibrated, boolean transferDamage) throws Exception {
        var parts = new ArrayList<EffectProgram>(); parts.add(AscensionTest.program(true).program());
        for (String name : List.of("acrobat_dodge", "radiant", "solar_effect_duration", "ember_of_solace", "empowering_damage")) {
            var data = json(name);
            if (name.equals("acrobat_dodge") && calibrated) json("acrobat_dodge_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e -> data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value", e.getValue()));
            parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
        }
        var calibration = json("acrobat_dodge_test_calibration").getAsJsonObject("program");
        if (transferDamage) for (var profile : calibration.getAsJsonArray("profiles")) if (profile.getAsJsonObject().get("id").getAsString().equals(D + "acrobat_ascension_damage"))
            for (var point : profile.getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("curve").getAsJsonArray("points")) point.getAsJsonObject().addProperty("output", 1);
        parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, calibration).getOrThrow());
        return CompiledEffects.link(parts);
    }
    static class Harness {
        final CompiledEffects p; EffectSession session; int cast, reads, failureAtImpulse;
        boolean alive = true, present = true, queryMissing, unknownStatus, unknownDamage;
        String life = "life-1"; double x;
        InputDirection.Outcome inputOutcome = InputDirection.Outcome.OBSERVED;
        Optional<WorldDirection> input = Optional.of(new WorldDirection("world", 1, 0, 1));
        Impulse.Outcome impulseOutcome = Impulse.Outcome.APPLIED; Displacement.Outcome ascentOutcome = Displacement.Outcome.APPLIED;
        final List<Impulse.Command> impulses = new ArrayList<>(); final List<Displacement.Command> moves = new ArrayList<>();
        final List<DamageCommand> hits = new ArrayList<>(); final List<TargetQuery> queries = new ArrayList<>(); final List<EntityAnchor.Query> anchors = new ArrayList<>();
        final List<StatusResult.Check> statuses = new ArrayList<>(); final Set<String> denied = new HashSet<>();
        Harness() throws Exception { this(true, EffectState.Mode.PVE); }
        Harness(boolean calibrated, EffectState.Mode mode) throws Exception { this(calibrated, mode, false); }
        Harness(boolean calibrated, EffectState.Mode mode, boolean transferDamage) throws Exception {
            this(program(calibrated, transferDamage), mode);
        }
        Harness(CompiledEffects program, EffectState.Mode mode) {
            p = program; session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty().withMode(mode), this::world);
            choose("class", "acrobat_dodge"); choose("air_move", "ascension"); bind("player", "test:acrobat_inputs");
        }
        WorldPosition position() { return new WorldPosition("world", x, 40, 0); }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            return switch (r.command()) {
                case EntityAnchor.Query q -> {
                    anchors.add(q); var ref = new EntityAnchor.Reference(q.target(), life, "world");
                    yield !present ? new EntityAnchor.Result(q, EntityAnchor.Outcome.MISSING, Optional.empty())
                            : q.expected().filter(e -> !e.equals(ref)).isPresent() ? new EntityAnchor.Result(q, EntityAnchor.Outcome.REPLACED, Optional.empty())
                            : new EntityAnchor.Result(q, EntityAnchor.Outcome.AVAILABLE, Optional.of(new EntityAnchor.Observation(ref, position(), q.anchor(), alive)));
                }
                case InputDirection.Query q -> { reads++; yield new InputDirection.Result(q, inputOutcome, input); }
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 0, 0, 1)));
                case Impulse.Command c -> {
                    impulses.add(c); if (impulses.size() == failureAtImpulse) throw new IllegalStateException("Unknown native leap");
                    yield impulseOutcome == Impulse.Outcome.APPLIED ? new Impulse.Receipt(c, impulseOutcome, Optional.of(new Impulse.Change(new Impulse.Velocity(0, 0, 0), c.delta().orElseThrow())))
                            : impulseOutcome == Impulse.Outcome.UNCHANGED ? new Impulse.Receipt(c, impulseOutcome, Optional.of(new Impulse.Change(new Impulse.Velocity(0, 0, 0), new Impulse.Velocity(0, 0, 0)))) : Impulse.Receipt.rejected(c, impulseOutcome);
                }
                case EntityQuery q -> new EntityQuery.Result(q, Optional.of(new EntityQuery.View(!q.target().equals("player") || alive, !q.target().equals("enemy"), 100, 100, 0)));
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(position()));
                case RelationQuery q -> new RelationQuery.Result(q, Optional.of(false));
                case TargetQuery q -> {
                    queries.add(q); List<TargetQuery.Target> targets = q.relation() == TargetQuery.Relation.ALLIED ? List.of(new TargetQuery.Target("ally", 10)) : List.of(new TargetQuery.Target("enemy", 0), new TargetQuery.Target("guardian", 3.25));
                    yield new TargetQuery.Result(q, queryMissing ? TargetQuery.Outcome.MISSING_CENTER : TargetQuery.Outcome.AVAILABLE, queryMissing ? List.of() : targets.stream().filter(t -> !q.exclude().contains(t.entity()) && (q.includeCenter() || !(q.center() instanceof TargetQuery.EntityCenter center) || !center.entity().equals(t.entity()))).sorted(q.comparator()).toList());
                }
                case StatusResult.Check q -> { statuses.add(q); if (unknownStatus && q.definition().id().equals(D + "radiant")) throw new IllegalStateException("Unknown Radiant grant"); yield new StatusResult.Checked(q, denied.contains(q.target()) ? StatusResult.Decision.DENIED : StatusResult.Decision.ALLOWED); }
                case DamageCommand c -> { hits.add(c); if (unknownDamage) throw new IllegalStateException("Unknown blast"); yield new DamageReceipt(r.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, c.amount(), Optional.empty(), false); }
                case Displacement.Command c -> { moves.add(c); double dy = ascentOutcome == Displacement.Outcome.APPLIED ? 3 : 0;
                    yield ascentOutcome == Displacement.Outcome.APPLIED || ascentOutcome == Displacement.Outcome.UNCHANGED
                            ? new Displacement.Receipt(c, ascentOutcome, Optional.of(new Displacement.Change(position(), new WorldPosition("world", x, 40 + dy, 0), new Displacement.Offset(0, dy, 0)))) : Displacement.Receipt.rejected(c, ascentOutcome); }
                case PickupCollection.Query q -> new PickupCollection.Result(q, PickupCollection.Outcome.FOUND, List.of());
                default -> throw new AssertionError(r.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void send(RuleEngine.Signal s) { session.start(now(), s); }
        void bind(String holder, String bundle) { send(SourceChange.bind(new EffectSource(holder + "/" + bundle, bundle, holder, new BuffInstance.Origin(holder, bundle, "", ""), Set.of()))); }
        void choose(String slot, String ability) { var old = state().abilities().getOrDefault("player", AbilityLoadout.EMPTY); var slots = new HashMap<>(old.slots()); if (ability == null) slots.remove(D + slot); else slots.put(D + slot, D + ability); send(new AbilityChange("player", old, new AbilityLoadout(slots)).signal()); }
        AbilityUse.Request request(String slot, boolean ground) { return new AbilityUse.Request("player", D + slot, "cast/" + ++cast, new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of(), Map.of("on_ground", ground), Map.of())); }
        AbilityUse.Receipt use(String slot, boolean ground) { var q = request(slot, ground); var receipt = (AbilityUse.Receipt) p.useAbility(state(), q).result(); send(q.signal()); return receipt; }
        void until(long time) { session.observe(time, List.of()); }
        double energy() { return state().resources().get(new ResourceState.Key("player", ENERGY)).value(); }
        Optional<BuffInstance> radiant(String holder) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(D + "radiant") && b.key().holder().equals(holder)).findFirst(); }
        void restore() { session = EffectSession.restore(p, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())), this::world); }
        void gain() { send(new RuleEngine.Signal("test:energy", new EffectEvent("player", "player", new BuffInstance.Origin("player", "gain", "", ""), Set.of(), Map.of()))); }
    }
    @Test void requiredCalibrationFailsBeforePaymentAndDefinitionsRoundTrip() throws Exception {
        var h = new Harness(false, EffectState.Mode.PVE); assertThrows(IllegalArgumentException.class, () -> h.p.useAbility(h.state(), h.request("class", true))); assertEquals(1, h.energy()); assertTrue(h.impulses.isEmpty());
        var p = program(true); assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
    }
    @Test void bothModesAcceptGroundOrAirUseAndPayOnceBeforeTwoRealMotionCommands() throws Exception {
        for (var mode : EffectState.Mode.values()) for (boolean ground : new boolean[] {true, false}) {
            var h = new Harness(true, mode); assertEquals(AbilityUse.Outcome.ACCEPTED, h.use("class", ground).outcome()); assertEquals(0, h.energy()); assertEquals(2, h.impulses.size());
            assertEquals(6 * Math.sqrt(.5), h.impulses.getFirst().delta().orElseThrow().x(), 1e-10); assertEquals(4, h.impulses.getLast().delta().orElseThrow().y());
            assertTrue(h.radiant("player").isEmpty()); h.until(199_999); assertTrue(h.hits.isEmpty()); h.until(200_000);
            assertEquals(10_200_000, h.radiant("player").orElseThrow().deadline()); assertEquals(10_200_000, h.radiant("ally").orElseThrow().deadline());
            assertEquals(List.of(4.0, 1.0), h.hits.stream().map(DamageCommand::amount).toList()); assertTrue(h.hits.stream().allMatch(c -> c.source().ability().equals(ABILITY) && c.tags().contains("chorus:class_damage")));
            assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.use("class", ground).outcome()); assertEquals(2, h.impulses.size());
        }
    }
    @Test void neutralUsesExplicitCalibrationWhileUnavailableInputNeverInventsMotionOrBenefits() throws Exception {
        var h = new Harness(); h.input = Optional.empty(); h.use("class", true); assertEquals(-6, h.impulses.getFirst().delta().orElseThrow().z()); h.until(200_000); assertTrue(h.radiant("player").isPresent());
        for (var outcome : InputDirection.Outcome.values()) if (outcome != InputDirection.Outcome.OBSERVED) {
            h = new Harness(); h.input = Optional.empty(); h.inputOutcome = outcome; h.use("class", false); h.until(200_000); assertTrue(h.impulses.isEmpty()); assertTrue(h.statuses.isEmpty()); assertTrue(h.energy() < .01);
        }
    }
    @Test void sameLifeCompletionSurvivesUnequipNewInputAndCheckpointButRejectsDeathOrReplacement() throws Exception {
        var h = new Harness(); h.use("class", false); h.choose("class", null); h.input = Optional.of(new WorldDirection("world", -1, 0, 0)); h.x = 50; h.restore(); h.until(200_000);
        assertEquals(1, h.reads); assertEquals(2, h.impulses.size()); assertTrue(h.radiant("player").isPresent()); assertEquals(ABILITY, h.radiant("player").orElseThrow().origin().ability()); assertEquals("life-1", h.anchors.getLast().expected().orElseThrow().life());
        for (String unavailable : List.of("dead", "missing", "replacement")) {
            var other = new Harness(); other.use("class", true); switch (unavailable) { case "dead" -> other.alive = false; case "missing" -> other.present = false; default -> other.life = "life-2"; }
            other.until(200_000); assertTrue(other.statuses.isEmpty()); assertTrue(other.hits.isEmpty());
        }
    }
    @Test void recipientSolaceAndAuthorizationDoNotLeakCasterDurationToOtherAllies() throws Exception {
        var h = new Harness(); h.bind("player", D + "ember_of_solace"); h.use("class", true); h.until(200_000);
        assertEquals(15_200_000, h.radiant("player").orElseThrow().deadline()); assertEquals(10_200_000, h.radiant("ally").orElseThrow().deadline());
        var other = new Harness(); other.bind("ally", D + "ember_of_solace"); other.denied.add("player"); other.use("class", false); other.until(200_000);
        assertTrue(other.radiant("player").isEmpty()); assertEquals(15_200_000, other.radiant("ally").orElseThrow().deadline()); assertEquals(2, other.hits.size());
    }
    @Test void baselineModeCesAndUnselectionUseTheSameSelectedClassAccount() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = new Harness(true, mode); h.use("class", false); h.gain(); assertEquals(.5, h.energy(), 1e-12); h.until(1_000_000);
            double baseline = 1 / (mode == EffectState.Mode.PVE ? 56.0 : 148.4); assertEquals(.5 + baseline, h.energy(), 1e-10);
            h.choose("class", "gamblers_dodge"); h.until(2_000_000); assertEquals(.5 + baseline, h.energy(), 1e-10);
        }
    }
    @Test void ascensionReusesRadiantWithoutLeapSecondPaymentOrWrongDamageAttribution() throws Exception {
        var h = new Harness(); h.bind("ally", D + "ember_of_solace"); h.use("air_move", false);
        assertEquals(0, h.energy()); assertTrue(h.impulses.isEmpty()); assertEquals(1, h.moves.size()); assertTrue(h.radiant("player").isEmpty());
        int ascentHits = h.hits.size(); h.choose("class", "marksman_dodge"); h.choose("air_move", null); h.restore(); h.until(200_000);
        assertEquals(ascentHits, h.hits.size(), "test policy only transfers Radiant"); assertEquals(D + "ascension", h.radiant("player").orElseThrow().origin().ability()); assertEquals(15_200_000, h.radiant("ally").orElseThrow().deadline()); assertEquals(0, h.energy());
    }
    @Test void onlyObservedAscensionMotionCanTransferBenefitsAndConfiguredBlastKeepsAscensionCredit() throws Exception {
        for (var outcome : List.of(Displacement.Outcome.MISSING_TARGET, Displacement.Outcome.UNLOADED, Displacement.Outcome.UNCHANGED)) {
            var h = new Harness(); h.ascentOutcome = outcome; h.use("air_move", false); h.until(200_000);
            assertEquals(outcome == Displacement.Outcome.UNCHANGED, h.radiant("player").isPresent()); assertTrue(h.impulses.isEmpty());
        }
        var h = new Harness(true, EffectState.Mode.PVE, true); h.use("air_move", false); h.until(200_000);
        var blast = h.hits.stream().filter(c -> c.tags().contains(D + "acrobat_dodge_damage")).toList();
        assertEquals(List.of(4.0, 1.0), blast.stream().map(DamageCommand::amount).toList()); assertTrue(blast.stream().allMatch(c -> c.source().ability().equals(D + "ascension"))); assertTrue(h.impulses.isEmpty());
    }
    @Test void rejectedImpulseSkipsCompletionWhileKnownUnchangedMotionCanComplete() throws Exception {
        for (var outcome : List.of(Impulse.Outcome.DEAD, Impulse.Outcome.UNLOADED, Impulse.Outcome.UNCHANGED)) {
            var h = new Harness(); h.impulseOutcome = outcome; h.use("class", false); h.until(200_000);
            assertEquals(outcome == Impulse.Outcome.UNCHANGED, h.radiant("player").isPresent()); assertEquals(outcome == Impulse.Outcome.UNCHANGED ? 2 : 1, h.impulses.size());
        }
    }
    @Test void unknownMotionOrLaterBenefitRetainsPaymentAndNeverReplaysCommittedWork() throws Exception {
        for (int phase : new int[] {1, 2, 3, 4}) {
            var h = new Harness(); h.failureAtImpulse = phase <= 2 ? phase : 0; h.unknownStatus = phase == 3; h.unknownDamage = phase == 4;
            if (phase <= 2) assertThrows(IllegalStateException.class, () -> h.use("class", false)); else { h.use("class", false); assertThrows(IllegalStateException.class, () -> h.until(200_000)); }
            int motions = h.impulses.size(), hits = h.hits.size(), grants = h.statuses.size(); assertTrue(h.energy() < .01);
            assertThrows(IllegalStateException.class, () -> h.until(1_000_000)); assertEquals(motions, h.impulses.size()); assertEquals(hits, h.hits.size()); assertEquals(grants, h.statuses.size());
            if (phase == 4) assertTrue(h.radiant("player").isPresent(), "prior Radiant grant was committed");
        }
    }
}
