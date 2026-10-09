package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ImpactSnapshotTest {
    private static final EffectSource SOURCE = source("test:impact_attack");
    private static final EffectSource OBSERVER = new EffectSource("observer", "test:impact_observer", "player", SOURCE.origin(), Set.of());
    private static DamageCommand attack() {
        return new DamageCommand("unknown", SOURCE.origin(), 10, "minecraft:generic", Set.of("test:impact"), Set.of("test:weapon_kill"), false, Optional.of("test:attack_damage"));
    }
    private static ImpactData distance(double distance) { return new ImpactData(Map.of("distance", new Measure(distance, Unit.METER))); }
    private static EffectState grant(CompiledEffects program, EffectState state, String id, String target) {
        return state.withBuffs(Buffs.grant(state.buffs(), program.buff(id), target, target, SOURCE.origin(), 1, 1, program.buff(id).timer().durationMicros()).store());
    }
    private static EffectState launch(CompiledEffects program) { return grant(program, EffectState.empty().withSource(SOURCE), "test:empower", "player"); }
    private static double hit(CompiledEffects program, EffectState state, DamageSnapshot snapshot, double distance) {
        return program.outgoing(state, snapshot.command("target", distance(distance)), 10).orElseThrow().output().value();
    }
    @Test void perVictimFalloffRunsAfterFrozenFlatAndEmpoweringStagesEvenWhenSourceIsGone() throws Exception {
        var program = load("impact_snapshot"); var snapshot = program.captureDamage(launch(program), attack());
        var impact = EffectState.empty();
        assertEquals(18, hit(program, impact, snapshot, 0), 1e-9);
        assertEquals(18, hit(program, impact, snapshot, 3), 1e-9);
        assertEquals(9, hit(program, impact, snapshot, 5), 1e-9, "(10 + 5) * 1.2 * .5; distance does not modify frozen base damage");
        assertEquals(0, hit(program, impact, snapshot, 7), 1e-9);
        assertEquals(18, hit(program, impact, snapshot, 0), 1e-9);
        assertEquals(10, snapshot.attack().amount()); assertEquals(ImpactData.EMPTY, snapshot.attack().impact());
        var result = program.outgoing(impact, snapshot.command("target", distance(5)), 10).orElseThrow();
        assertEquals(3, result.trace().contributions().size());
        assertEquals("test-impact-v1", result.trace().version());
    }
    @Test void onUseImpactConditionStaysSymbolicUntilEachHit() throws Exception {
        var data = json("impact_snapshot");
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().add("if", JsonParser.parseString("""
                {"type":"chorus:compare","left":{"type":"chorus:impact_number","name":"distance","unit":"meter"},
                 "op":"ge","right":{"type":"chorus:constant","value":5,"unit":"meter"}}
                """));
        var program = compile(data); var snapshot = program.captureDamage(launch(program), attack());
        assertEquals(12, hit(program, EffectState.empty(), snapshot, 0), 1e-9);
        assertEquals(9, hit(program, EffectState.empty(), snapshot, 5), 1e-9);
    }
    @Test void liveMaxContributionsAndCurrentDefenseReceiveTheSameImpactMeasurements() throws Exception {
        var program = load("impact_snapshot"); var snapshot = program.captureDamage(launch(program), attack());
        var state = grant(program, EffectState.empty().withSource(new EffectSource("live", "test:live_impact", "player", SOURCE.origin(), Set.of())), "test:vulnerability", "target");
        assertEquals(18, hit(program, state, snapshot, 0), 1e-9);
        assertEquals(10.5, hit(program, state, snapshot, 5), 1e-9, "MAX(.2, .4), then falloff");
        assertEquals(15.75, program.defense(state, snapshot.command("target", distance(5)), 10.5).orElseThrow().output().value(), 1e-9);
        assertEquals(18, program.defense(state, snapshot.command("target", distance(0)), 18).orElseThrow().output().value(), 1e-9);
    }
    @Test void delayedJsonLoopPassesCapturedDistancesAndReactionsReadEachActualResult() throws Exception {
        var program = load("impact_snapshot"); var requests = new ArrayList<RuleEngine.WorldRequest>();
        var sessionRef = new EffectSession[1];
        var session = new EffectSession(engine(program), launch(program).withSource(OBSERVER), request -> {
            requests.add(request);
            return switch (request.command()) {
                case PositionQuery query -> new PositionQuery.Result(query, Optional.of(new WorldPosition("test:world", 0, 0, 0)));
                case TargetQuery query -> new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE,
                        List.of(new TargetQuery.Target("near", 0), new TargetQuery.Target("far", 5), new TargetQuery.Target("edge", 7)));
                case DamageCommand command -> new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0,
                        program.outgoing(sessionRef[0].state().engine().domain(), command, command.amount()).orElseThrow().output().value(), Optional.empty(), false);
                default -> throw new AssertionError(request.command());
            };
        });
        sessionRef[0] = session;
        session.start(0, new RuleEngine.Signal("test:burst", event(SOURCE)));
        session.start(0, SourceChange.remove(SOURCE.instance())); session.observe(100_000, List.of());
        assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty());
        var commands = requests.stream().map(RuleEngine.WorldRequest::command).filter(DamageCommand.class::isInstance).map(DamageCommand.class::cast).toList();
        assertEquals(List.of(0d, 5d, 7d), commands.stream().map(c -> c.impact().number("distance", Unit.METER).value()).toList());
        assertEquals(1, commands.stream().map(DamageCommand::snapshot).distinct().count());
        assertEquals(5, requests.stream().map(RuleEngine.WorldRequest::id).distinct().count());
        assertEquals(18, buff(session.state(), "test:impact_log", "near").components().numbers().get("actual"));
        assertEquals(9, buff(session.state(), "test:impact_log", "far").components().numbers().get("actual"));
        assertEquals(5, buff(session.state(), "test:impact_log", "far").components().numbers().get("distance"));
        assertEquals(0, buff(session.state(), "test:impact_log", "edge").components().numbers().get("actual"));
        assertTrue(session.state().engine().domain().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("test:empower")));
    }
    @Test void immediateActionSuppliesImpactValuesAndTheWholeJsonProgramRoundTrips() throws Exception {
        var program = load("impact_snapshot"); var engine = engine(program);
        var waiting = send(engine, engine.initial(launch(program)), 0, "test:immediate",
                new EffectEvent("player", "target", SOURCE.origin(), Set.of(), Map.of("distance", new Measure(5, Unit.METER))));
        var command = (DamageCommand) waiting.actions().getFirst().command();
        assertEquals(distance(5), command.impact()); assertTrue(command.snapshot().isEmpty());
        assertEquals(9, program.outgoing(waiting.state().engine().domain(), command, 10).orElseThrow().output().value(), 1e-9);
        assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program.program()).getOrThrow()).getOrThrow().program());
    }
    @Test void missingOrWrongUnitsFailAtImpactAndCaptureCannotAbsorbPerHitInputs() throws Exception {
        var program = load("impact_snapshot"); var state = launch(program); var snapshot = program.captureDamage(state, attack());
        assertThrows(IllegalArgumentException.class, () -> program.outgoing(state, snapshot.command("target"), 10));
        assertThrows(IllegalArgumentException.class, () -> program.outgoing(state, snapshot.command("target", new ImpactData(Map.of("distance", new Measure(5, Unit.SECOND)))), 10));
        var a = attack();
        assertThrows(IllegalArgumentException.class, () -> program.captureDamage(state, new DamageCommand(a.target(), a.source(), a.amount(), a.damageType(), a.tags(), a.killTags(), false, a.scalingProfile(), Optional.empty(), distance(5))));
        var bad = json("impact_snapshot");
        bad.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("modifiers").get(1).getAsJsonObject().getAsJsonObject("value").getAsJsonObject("of").addProperty("unit", "second");
        assertThrows(RuntimeException.class, () -> compile(bad));
        assertThrows(IllegalArgumentException.class, () -> new Value.ImpactNumber(" ", Unit.METER));
        var measurements = new HashMap<>(distance(5).numbers()); var impact = new ImpactData(measurements); measurements.clear();
        assertEquals(distance(5), impact); assertThrows(UnsupportedOperationException.class, () -> impact.numbers().clear());
        assertNotEquals(snapshot.command("target", distance(3)), snapshot.command("target", distance(5)));
    }
    @Test void impactInputsCannotReplaceReceiptFactsAndSurviveHitDeathAndKillEvents() throws Exception {
        var program = load("impact_snapshot"); var snapshot = program.captureDamage(launch(program), attack());
        var impact = new ImpactData(Map.of("distance", new Measure(5, Unit.METER), "effective_damage", new Measure(999, Unit.DAMAGE)));
        var command = snapshot.command("target", impact);
        var facts = DamageFacts.from(command, new DamageReceipt("actual", DamageReceipt.Outcome.APPLIED, 0, 0, 2, Optional.of("death"), false));
        assertEquals(List.of("chorus:hit", "chorus:damage_taken", "chorus:death", "chorus:kill"), facts.stream().map(RuleEngine.Signal::type).toList());
        for (var signal : facts) {
            var event = (EffectEvent) signal.payload(); assertEquals(impact, event.impact());
            assertEquals(2, event.numbers().get("effective_damage").value());
            assertEquals(999, event.impact().number("effective_damage", Unit.DAMAGE).value());
        }
    }
    @Test void shieldLayerExpressionsSeeImpactWithoutChangingTheIncomingDamageBudget() throws Exception {
        var data = json("shields");
        data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("shield").add("taken_multiplier", JsonParser.parseString("""
                {"type":"chorus:impact_number","name":"shield_factor","unit":"multiplier"}
                """));
        var program = compile(data); var source = source("test:shield_actions"); var engine = engine(program);
        var armed = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:arm", event(source)).state().engine().domain();
        var impact = new ImpactData(Map.of("shield_factor", new Measure(.5, Unit.MULTIPLIER)));
        var command = new DamageCommand("target", source.origin(), 100, "minecraft:generic", Set.of(), Set.of(), false, Optional.empty(), Optional.empty(), impact);
        var plan = program.shields(armed, command, 100);
        assertEquals(55, plan.budget().shieldLoss(), 1e-9, "45 capacity consumes 90 input; second layer consumes remaining 10");
        var facts = DamageFacts.from(command, new DamageReceipt("shield", DamageReceipt.Outcome.APPLIED, 55, 0, 0, Optional.empty(), false, Optional.empty(), plan.layers()));
        assertTrue(facts.stream().anyMatch(s -> s.type().equals("chorus:shield_broken")));
        assertTrue(facts.stream().allMatch(s -> ((EffectEvent) s.payload()).impact().equals(impact)));
    }
}
