package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectileFuseTest {
    static WorldPosition at(double x, double y) { return new WorldPosition("world", x, y, 0); }
    static ProjectileProximity fuse(double radius, double arm) { return new ProjectileProximity(radius, arm, TargetQuery.Anchor.BODY, TargetQuery.Relation.NOT_ALLIED, true); }
    static JsonArray actions(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do"); }
    static JsonObject spec(JsonObject data) { return actions(data).get(3).getAsJsonObject().getAsJsonObject("projectile"); }
    @Test void highSpeedSweepFindsEntryWithBothEndpointsOutsideAndAtTangency() {
        assertEquals(.4, fuse(1, 0).entry(at(0, 0), at(10, 0), at(5, 0), 0).orElseThrow(), 1e-12);
        assertEquals(.5, fuse(1, 0).entry(at(0, 0), at(10, 0), at(5, 1), 0).orElseThrow(), 1e-12);
        assertTrue(fuse(1, 0).entry(at(0, 0), at(10, 0), at(5, 1.01), 0).isEmpty());
        assertTrue(fuse(1, 0).entry(at(0, 0), at(10, 0), at(-2, 0), 0).isEmpty());
    }
    @Test void armingInsideSphereTriggersAtArmingPointButDoesNotReplayAPassedSphere() {
        assertEquals(.5, fuse(1, 5).entry(at(0, 0), at(10, 0), at(5, 0), 0).orElseThrow(), 1e-12);
        assertTrue(fuse(1, 7).entry(at(0, 0), at(10, 0), at(5, 0), 0).isEmpty());
        assertTrue(fuse(1, 11).entry(at(0, 0), at(10, 0), at(9, 0), 0).isEmpty());
        assertEquals(.25, fuse(2, 5).entry(at(3, 0), at(7, 0), at(5, 0), 4).orElseThrow(), 1e-12);
    }
    @Test void stationaryOverlapRequiresAlreadyArmedAndForeignGeometryCannotBeMeasured() {
        assertEquals(0, fuse(1, 3).entry(at(0, 0), at(0, 0), at(.5, 0), 3).orElseThrow());
        assertTrue(fuse(1, 3).entry(at(0, 0), at(0, 0), at(.5, 0), 2).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> fuse(1, 0).entry(at(0, 0), at(1, 0), new WorldPosition("other", 0, 0, 0), 0));
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> fuse(invalid, 0));
            assertThrows(IllegalArgumentException.class, () -> fuse(1, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> fuse(0, 0));
    }
    @Test void launchFreezesOptionalPoliciesAndFullDeclarationsRoundTrip() throws Exception {
        var program = load("projectile_fuse"); var h = new ProjectileTest.Harness(program); h.fire(); var launch = h.launches.getFirst();
        assertEquals(6, launch.parameters().range().orElseThrow()); assertEquals(fuse(1, 0), launch.parameters().proximity().orElseThrow());
        assertEquals(launch, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(launch), ProjectileFlight.Launch.class));
        assertEquals(program.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program.program()).getOrThrow()).getOrThrow());
        var legacy = new ProjectileTest.Harness(); legacy.fire(); assertTrue(legacy.launches.getFirst().parameters().range().isEmpty()); assertTrue(legacy.launches.getFirst().parameters().proximity().isEmpty());
    }
    @Test void strictDeclarationRejectsUnknownFieldsWrongUnitsAndInvalidFuseGeometry() throws Exception {
        for (String fault : List.of("range_unit", "range_negative", "radius_unit", "zero_radius", "negative_arming", "missing_arming", "typo", "wrong_binding")) {
            var data = json("projectile_fuse"); var s = spec(data); var p = s.getAsJsonObject("proximity");
            switch (fault) {
                case "range_unit" -> s.getAsJsonObject("range").addProperty("unit", "second");
                case "range_negative" -> s.getAsJsonObject("range").addProperty("value", -1);
                case "radius_unit" -> p.getAsJsonObject("radius").addProperty("unit", "second");
                case "zero_radius" -> p.getAsJsonObject("radius").addProperty("value", 0);
                case "negative_arming" -> p.getAsJsonObject("arm_distance").addProperty("value", -1);
                case "missing_arming" -> p.remove("arm_distance");
                case "typo" -> p.addProperty("arming", 1);
                case "wrong_binding" -> s.add("range", JsonParser.parseString("{\"type\":\"chorus:result\",\"binding\":\"impact\",\"field\":\"distance\"}"));
            }
            assertThrows(RuntimeException.class, () -> compile(data), fault);
        }
    }
    @Test void newEndsAreTerminalObservationsWithoutInventedCollisionCredit() throws Exception {
        for (var end : List.of(ProjectileFlight.End.RANGE, ProjectileFlight.End.PROXIMITY)) {
            var h = new ProjectileTest.Harness(load("projectile_fuse")); h.fire();
            var target = end.hasTarget() ? Optional.of("enemy") : Optional.<String>empty();
            var impact = new ProjectileFlight.Impact(end, at(5, 0), target, 0, 0, 0, 50_000);
            var progress = ProjectileFlight.Progress.EMPTY.contact(ProjectileFlight.Collision.STOP, end, target, false);
            assertTrue(progress.terminal()); assertEquals(0, progress.entityContacts()); assertTrue(progress.hits().isEmpty());
            assertEquals(0, impact.entityContacts()); assertEquals(end.hasTarget() ? 1 : 0, impact.targets().size());
            h.finish(0, impact);
            assertEquals(List.of("test:" + end.name().toLowerCase(Locale.ROOT)), h.commands.stream().filter(Action.CueCommand.class::isInstance).map(Action.CueCommand.class::cast).map(Action.CueCommand::cue).toList());
            assertThrows(IllegalArgumentException.class, () -> new ProjectileFlight.Impact(end, at(5, 0), target, 0, 0, 0, 0, 1, 0, 0, 0, false));
        }
    }
    @Test void retiredRuntimeAndDeclarationShapesMigrateWithoutAddingFusePolicy() throws Exception {
        var data = json("projectile_fuse"); spec(data).remove("range"); spec(data).remove("proximity");
        var h = new ProjectileTest.Harness(compile(data)); h.fire(); var codec = EffectCheckpointCodec.DATA;
        var declaration = ((EffectProgram.Projectile) h.program.program().bundles().getFirst().rules().getFirst().actions().get(3)).spec();
        for (var value : List.of(h.launches.getFirst().parameters(), declaration)) {
            var wire = codec.encode(value).getAsJsonObject(); String id = wire.get("$").getAsString(); assertTrue(id.endsWith("_v2")); wire.addProperty("$", id.substring(0, id.length() - 3));
            assertThrows(IllegalArgumentException.class, () -> codec.decode(wire, value.getClass()));
            wire.getAsJsonObject("v").remove("range"); wire.getAsJsonObject("v").remove("proximity");
            assertEquals(value, codec.decode(wire, value.getClass()));
        }
        for (var end : List.of(ProjectileFlight.End.ENTITY, ProjectileFlight.End.BLOCK, ProjectileFlight.End.EXPIRED, ProjectileFlight.End.UNLOADED, ProjectileFlight.End.ARRIVED, ProjectileFlight.End.TARGET_LOST, ProjectileFlight.End.CAUGHT)) {
            var wire = codec.encode(end).getAsJsonObject(); wire.addProperty("$", "chorus:effect/projectile/projectile_flight/end"); assertEquals(end, codec.decode(wire, ProjectileFlight.End.class));
        }
        var invented = codec.encode(ProjectileFlight.End.PROXIMITY).getAsJsonObject(); invented.addProperty("$", "chorus:effect/projectile/projectile_flight/end");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(invented, ProjectileFlight.End.class));
    }
    static int retireDeclarations(JsonElement tree) {
        int changed = 0;
        if (tree.isJsonArray()) for (var child : tree.getAsJsonArray()) changed += retireDeclarations(child);
        else if (tree.isJsonObject()) {
            var object = tree.getAsJsonObject();
            if (object.has("$") && object.get("$").getAsString().equals("chorus:effect/data/projectile_spec_v2")) {
                object.addProperty("$", "chorus:effect/data/projectile_spec"); object.getAsJsonObject("v").remove("range"); object.getAsJsonObject("v").remove("proximity"); changed++;
            }
            for (var field : object.entrySet()) changed += retireDeclarations(field.getValue());
        }
        return changed;
    }
    @Test void pendingDetachedLaunchRestoresBothNewFuseAndRetiredUnrestrictedProgram() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            var data = json(legacy ? "projectile" : "projectile_fuse"); var body = actions(data); var launch = body.remove(3);
            var delay = JsonParser.parseString("{\"after\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"second\"},\"lifetime\":\"detached\",\"do\":[]}").getAsJsonObject(); delay.getAsJsonArray("do").add(launch); body.add(delay);
            var program = compile(data); var source = source("test:projectile"); var launches = new ArrayList<ProjectileFlight.Launch>();
            java.util.function.Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world = request -> switch (request.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(at(0, 0)));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 1, 0, 0)));
                case ProjectileFlight.Launch l -> { launches.add(l); yield new ProjectileFlight.Receipt(l, ProjectileFlight.Outcome.LAUNCHED, Optional.of("flight")); }
                default -> throw new AssertionError(request.command());
            };
            var session = new EffectSession(program, EffectCheckpointTest.clock(), 1, EffectState.empty().withSource(source), world);
            session.start(0, new RuleEngine.Signal("test:launch", event(source))); session.start(0, SourceChange.remove(source.instance()));
            assertTrue(launches.isEmpty()); var saved = EffectCheckpointCodec.encode(session.checkpoint());
            if (legacy) assertTrue(retireDeclarations(saved) > 0);
            var restored = EffectSession.restore(program, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(saved), world);
            restored.observe(1_000_000, List.of()); assertEquals(1, launches.size()); assertTrue(restored.state().idle());
            var parameters = launches.getFirst().parameters();
            if (legacy) { assertTrue(parameters.range().isEmpty()); assertTrue(parameters.proximity().isEmpty()); }
            else { assertEquals(6, parameters.range().orElseThrow()); assertEquals(fuse(1, 0), parameters.proximity().orElseThrow()); }
        }
    }
}
