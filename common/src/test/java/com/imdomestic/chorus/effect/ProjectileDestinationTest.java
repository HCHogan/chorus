package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectileDestinationTest {
    static WorldPosition point(double x, double y, double z) { return new WorldPosition("world", x, y, z); }
    static ProjectileDestination destination(double radius) { return new ProjectileDestination("receiver", 720, radius, TargetQuery.Anchor.BODY, false); }
    static JsonObject returnSpec(JsonObject d) { return d.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use").get(5).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonArray("then").get(0).getAsJsonObject().getAsJsonObject("projectile"); }
    @Test void sweptArrivalFindsFirstEntryEvenWhenBothEndpointsAreOutside() {
        var d = destination(1); assertEquals(.4, d.entry(point(0, 0, 0), point(10, 0, 0), point(5, 0, 0)).orElseThrow(), 1e-12);
        assertEquals(.4, d.entry(point(10, 0, 0), point(0, 0, 0), point(5, 0, 0)).orElseThrow(), 1e-12);
        assertEquals(.5, d.entry(point(0, 0, 0), point(10, 0, 0), point(5, 1, 0)).orElseThrow(), 1e-12);
        assertTrue(d.entry(point(0, 0, 0), point(10, 0, 0), point(5, 1.000001, 0)).isEmpty());
        assertEquals(.5, destination(0).entry(point(0, 0, 0), point(10, 0, 0), point(5, 0, 0)).orElseThrow());
    }
    @Test void arrivalIncludesClosedEndpointsAndStationaryOverlapButNotMovingAway() {
        var d = destination(1); var center = point(5, 0, 0);
        assertEquals(1, d.entry(point(0, 0, 0), point(4, 0, 0), center).orElseThrow());
        assertEquals(0, d.entry(point(4, 0, 0), point(0, 0, 0), center).orElseThrow());
        assertEquals(0, d.entry(center, center, center).orElseThrow());
        assertTrue(d.entry(point(0, 0, 0), point(0, 0, 0), center).isEmpty());
        assertTrue(d.entry(point(0, 0, 0), point(-10, 0, 0), center).isEmpty());
    }
    @Test void arrivalDoesNotInventAnEntityHitOrConsumePiercingCounts() {
        var state = ProjectileFlight.Progress.EMPTY.contact(ProjectileFlight.Collision.STOP, ProjectileFlight.End.ARRIVED, Optional.of("receiver"), false);
        assertTrue(state.terminal()); assertEquals(1, state.sequence()); assertEquals(0, state.entityContacts()); assertTrue(state.hits().isEmpty());
        var receipt = new ProjectileFlight.Impact(ProjectileFlight.End.ARRIVED, point(1, 2, 3), Optional.of("receiver"), 0, 0, 0, 50_000);
        assertTrue(ResultShape.PROJECTILE_IMPACT.flag("arrived", receipt)); assertFalse(ResultShape.PROJECTILE_IMPACT.flag("entity", receipt));
        assertEquals(List.of(new Targets.Identity("receiver")), receipt.targets()); assertEquals(0, receipt.targetContacts());
        var lost = new ProjectileFlight.Impact(ProjectileFlight.End.TARGET_LOST, point(1, 2, 3), Optional.empty(), 0, 0, 0, 50_000);
        assertTrue(ResultShape.PROJECTILE_IMPACT.flag("target_lost", lost)); assertTrue(lost.targets().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ProjectileFlight.Impact(ProjectileFlight.End.ARRIVED, point(1, 2, 3), Optional.empty(), 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProjectileFlight.Impact(ProjectileFlight.End.ARRIVED, point(1, 2, 3), Optional.of("r"), 0, 0, 0, 1, 1, 0, 0, 0, false));
    }
    @Test void invalidDestinationNumericsIdentityAndDimensionsAreRejected() {
        for (double n : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> destination(n));
            assertThrows(IllegalArgumentException.class, () -> new ProjectileDestination("a", n, 1, TargetQuery.Anchor.BODY, true));
        }
        assertThrows(IllegalArgumentException.class, () -> new ProjectileDestination("", 1, 1, TargetQuery.Anchor.BODY, true));
        assertThrows(IllegalArgumentException.class, () -> destination(1).entry(point(0, 0, 0), point(1, 1, 1), new WorldPosition("other", 0, 0, 0)));
    }
    @Test void destinationCodecRoundTripsAndRejectsConflictingModesUnitsAndUnknownTargets() throws Exception {
        var d = json("projectile_return"); var program = compile(d).program();
        assertEquals(program, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow());
        for (String fault : List.of("radius", "rate", "unit", "target", "both", "typo")) {
            var data = d.deepCopy(); var spec = returnSpec(data); var destination = spec.getAsJsonObject("destination");
            switch (fault) {
                case "radius" -> destination.getAsJsonObject("arrival_radius").addProperty("value", -1);
                case "rate" -> destination.getAsJsonObject("turn_rate").addProperty("value", -1);
                case "unit" -> destination.getAsJsonObject("arrival_radius").addProperty("unit", "second");
                case "target" -> destination.add("target", JsonParser.parseString("{\"binding\":\"missing\"}"));
                case "both" -> spec.add("tracking", JsonParser.parseString("{\"radius\":{\"type\":\"chorus:constant\",\"value\":2,\"unit\":\"meter\"},\"turn_rate\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"degree_per_second\"}}"));
                case "typo" -> destination.addProperty("arrival_radiu", 1);
            }
            assertThrows(RuntimeException.class, () -> compile(data), fault);
        }
        assertTrue(new ProjectileFlight.Parameters(1, 0, 1, 1000).destination().isEmpty());
    }
    static final class Harness {
        final EffectSession session; final List<ProjectileFlight.Launch> launches = new ArrayList<>(); final List<Double> heals = new ArrayList<>();
        Harness() throws Exception { this("projectile_return"); }
        Harness(String fixture) throws Exception {
            session = new EffectSession(engine(load(fixture)), EffectState.empty(), request -> switch (request.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(point(1, 40, 3)));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 0, 1, 0)));
                case ProjectileFlight.Launch launch -> { launches.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("flight-" + launches.size())); }
                case DamageCommand d -> new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, d.amount(), Optional.empty(), false);
                case HealingCommand heal -> { heals.add(heal.amount()); yield new HealingReceipt(request.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0); }
                case Action.CueCommand cue -> RuleEngine.Empty.INSTANCE;
                default -> throw new AssertionError(request.command());
            });
            session.start(0, new AbilityChange("player", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:melee", "test:return"))).signal());
            session.start(0, new AbilityUse.Request("player", "test:melee", "cast", new EffectEvent("player", "player", new BuffInstance.Origin("player", "", "", ""), Set.of(), Map.of())).signal());
        }
        EffectState state() { return session.state().engine().domain(); }
        double energy() { return state().resources().get(new ResourceState.Key("player", "test:energy")).value(); }
        void complete(int flight, long time, ProjectileFlight.End end, Optional<String> target) { session.start(time, launches.get(flight).finish(new ProjectileFlight.Impact(end, point(1, 45, 3), target, 0, 0, 0, time))); }
    }
    @Test void nestedReturnCapturesReceiverAndCostHandleAndRefundsOnlyUponArrival() throws Exception {
        var h = new Harness(); assertEquals(1, h.energy()); assertEquals(1, h.launches.size());
        h.complete(0, 100_000, ProjectileFlight.End.ENTITY, Optional.of("enemy")); assertEquals(2, h.launches.size()); assertEquals(1, h.energy());
        var back = h.launches.getLast(); assertEquals("player", back.parameters().destination().orElseThrow().target()); assertEquals(point(1, 45, 3), back.position().orElseThrow());
        h.session.start(100_000, new AbilityChange("player", h.state().abilities().get("player"), AbilityLoadout.EMPTY).signal());
        h.complete(1, 200_000, ProjectileFlight.End.ARRIVED, Optional.of("player")); assertEquals(2, h.energy()); assertEquals(List.of(10.0), h.heals); assertTrue(h.state().retainedCosts().isEmpty());
    }
    @Test void lostDestinationCannotRefundAndUnusedCostRightExpires() throws Exception {
        var h = new Harness(); h.complete(0, 100_000, ProjectileFlight.End.ENTITY, Optional.of("enemy"));
        h.complete(1, 200_000, ProjectileFlight.End.TARGET_LOST, Optional.empty()); assertEquals(1, h.energy()); assertTrue(h.heals.isEmpty());
        h.session.observe(3_000_000, List.of()); assertTrue(h.state().retainedCosts().isEmpty());
    }
}
