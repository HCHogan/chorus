package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectileTest {
    private static final WorldPosition POINT = new WorldPosition("world", 1, 40, 3);
    private static final EffectSource SOURCE = source("test:projectile");
    private static JsonArray actions(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do"); }
    static final class Harness {
        final CompiledEffects program;
        final EffectSession session;
        final List<RuleEngine.WorldCommand> commands = new ArrayList<>();
        final List<ProjectileFlight.Launch> launches = new ArrayList<>();
        final List<Double> damage = new ArrayList<>();
        Harness() throws Exception { this(load("projectile")); }
        Harness(CompiledEffects program) {
            this.program = program;
            var power = new EffectSource("boost", "test:power", "player", SOURCE.origin(), Set.of());
            session = new EffectSession(engine(program), EffectState.empty().withSource(SOURCE).withSource(power), request -> {
                commands.add(request.command());
                return switch (request.command()) {
                    case PositionQuery q -> new PositionQuery.Result(q, Optional.of(POINT));
                    case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 1, 0, 0)));
                    case ProjectileFlight.Launch launch -> { launches.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("entity" + launches.size())); }
                    case DamageCommand d -> {
                        double value = program.outgoing(state(), d, d.amount()).orElseThrow().output().value(); damage.add(value);
                        yield new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, value, Optional.empty(), false);
                    }
                    case Action.CueCommand cue -> RuleEngine.Empty.INSTANCE;
                    case HealingCommand heal -> HealingReceipt.unapplied(request.id().toString(), heal, HealingReceipt.Outcome.MISSING);
                    case TargetQuery q -> new TargetQuery.Result(q, TargetQuery.Outcome.AVAILABLE, List.of());
                    default -> throw new AssertionError(request.command());
                };
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        void fire() { session.start(state().buffs().timeMicros(), new RuleEngine.Signal("test:launch", event(SOURCE))); }
        void finish(int index, ProjectileFlight.Impact hit) { session.start(hit.ageMicros(), launches.get(index).finish(hit)); assertTrue(session.state().idle()); }
    }
    @Test void physicalWaitRetainsDamageScopeAndLexicalValuesAfterAllSourcesDetach() throws Exception {
        var h = new Harness(); h.fire(); assertEquals(1, h.launches.size()); assertTrue(h.damage.isEmpty()); assertTrue(h.state().timers().isEmpty());
        h.session.start(0, SourceChange.remove("perk")); h.session.start(0, SourceChange.remove("boost"));
        h.finish(0, new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, POINT, Optional.of("enemy"), 0, 0, 0, 200_000));
        assertEquals(List.of(20.0), h.damage);
        var attack = h.commands.stream().filter(DamageCommand.class::isInstance).map(DamageCommand.class::cast).findFirst().orElseThrow();
        assertEquals("enemy", attack.target()); assertEquals(SOURCE.origin(), attack.source());
        var heal = (HealingCommand) h.commands.getLast(); assertEquals("player", heal.target()); assertEquals(20, heal.amount());
    }
    @Test void blockImpactIsAWorldPointAndAnEmptyTargetCollectionWhileExpiryRunsOnlyItsBranch() throws Exception {
        var h = new Harness(); h.fire();
        var point = new WorldPosition("world", 9.5, 40, 3);
        h.finish(0, new ProjectileFlight.Impact(ProjectileFlight.End.BLOCK, point, Optional.empty(), -1, 0, 0, 150_000));
        assertTrue(h.damage.isEmpty()); assertEquals(new TargetQuery.PositionCenter(point), ((TargetQuery) h.commands.getLast()).center());
        var expired = new Harness(); expired.fire(); expired.finish(0, new ProjectileFlight.Impact(ProjectileFlight.End.EXPIRED, POINT, Optional.empty(), 0, 0, 0, 1_000_000));
        assertTrue(expired.damage.isEmpty()); assertEquals(1, ((HealingCommand) expired.commands.getLast()).amount());
    }
    @Test void separateLaunchesPreserveIndependentContinuationsAndCannotChangeDimension() throws Exception {
        var h = new Harness(); h.fire(); h.fire(); assertNotEquals(h.launches.getFirst().continuation().id(), h.launches.getLast().continuation().id());
        h.finish(1, new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, POINT, Optional.of("b"), 0, 0, 0, 100_000));
        h.finish(0, new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, POINT, Optional.of("a"), 0, 0, 0, 200_000));
        assertEquals(List.of("b", "a"), h.commands.stream().filter(DamageCommand.class::isInstance).map(DamageCommand.class::cast).map(DamageCommand::target).toList());
        assertThrows(IllegalArgumentException.class, () -> h.launches.getFirst().finish(new ProjectileFlight.Impact(ProjectileFlight.End.EXPIRED, new WorldPosition("other", 1, 2, 3), Optional.empty(), 0, 0, 0, 1)));
    }
    @Test void codecsEnforceUnitsTypedGeometryLexicalBoundariesAndNoCrossFrameRefundClaims() throws Exception {
        var p = load("projectile").program(); assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        for (String fault : List.of("unit", "direction", "position", "shadow", "negative", "escape", "refund")) {
            var data = json("projectile"); var steps = actions(data); var launch = steps.get(3).getAsJsonObject(); var spec = launch.getAsJsonObject("projectile");
            switch (fault) {
                case "unit" -> spec.getAsJsonObject("speed").addProperty("unit", "meter");
                case "direction" -> spec.addProperty("direction", "muzzle");
                case "position" -> spec.addProperty("position", "aim");
                case "shadow" -> launch.addProperty("as", "shot");
                case "negative" -> spec.getAsJsonObject("gravity").addProperty("value", -1);
                case "escape" -> steps.add(JsonParser.parseString("{\"for_each\":\"impact\",\"as\":\"t\",\"do\":[]}"));
                case "refund" -> data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use").get(3).getAsJsonObject().getAsJsonArray("do").add(JsonParser.parseString("{\"type\":\"chorus:refund_cost\",\"cost\":\"cast_cost\",\"fraction\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"multiplier\"}}"));
            }
            assertThrows(RuntimeException.class, () -> compile(data), fault);
        }
    }
    @Test void terminalGeometryAndLaunchReceiptsRejectContradictoryObservations() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, POINT, Optional.empty(), 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProjectileFlight.Impact(ProjectileFlight.End.BLOCK, POINT, Optional.empty(), 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProjectileFlight.Impact(ProjectileFlight.End.EXPIRED, POINT, Optional.empty(), 1, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProjectileFlight.Parameters(2001, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProjectileFlight.Parameters(1, 0, 1.1, 1));
        var impact = new ProjectileFlight.Impact(ProjectileFlight.End.BLOCK, POINT, Optional.empty(), 0, 1, 0, 50_000);
        assertEquals(1, ResultShape.PROJECTILE_IMPACT.read("normal_y", impact).value()); assertTrue(ResultShape.PROJECTILE_IMPACT.flag("block", impact));
    }
    @Test void launchReceiptMustMatchRequestAndDuplicateCompletionCannotLaunchAgain() throws Exception {
        var engine = engine(load("projectile")); var waiting = send(engine, engine.initial(EffectState.empty().withSource(SOURCE)), 0, "test:launch", event(SOURCE));
        waiting = complete(engine, waiting, new PositionQuery.Result((PositionQuery) waiting.actions().getFirst().command(), Optional.of(POINT)));
        waiting = complete(engine, waiting, new DirectionQuery.Result((DirectionQuery) waiting.actions().getFirst().command(), Optional.of(new WorldDirection("world", 1, 0, 0))));
        var operation = waiting.actions().getFirst(); var launch = (ProjectileFlight.Launch) operation.command();
        var receipt = new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("physical"));
        var settled = complete(engine, waiting, receipt); var duplicate = engine.transition(settled.state(), new RuleEngine.Completed(operation.id(), receipt));
        assertEquals(settled.state(), duplicate.state()); assertTrue(duplicate.actions().isEmpty());
        var rejected = complete(engine, waiting, new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.REJECTED, Optional.empty()));
        assertTrue(rejected.state().idle() && rejected.actions().isEmpty());
        var other = new ProjectileFlight.Launch(launch.position(), launch.direction(), launch.parameters(), "other", launch.continuation(), launch.impactSlot());
        var invalid = engine.transition(waiting.state(), new RuleEngine.Completed(operation.id(), new ProjectileFlight.Receipt(other, ProjectileFlight.Outcome.LAUNCHED, Optional.of("physical"))));
        while (invalid.needsPump()) invalid = engine.transition(invalid.state(), RuleEngine.Pump.INSTANCE);
        assertTrue(invalid.state().engine().failure().isPresent()); assertTrue(invalid.actions().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.empty()));
    }
}
