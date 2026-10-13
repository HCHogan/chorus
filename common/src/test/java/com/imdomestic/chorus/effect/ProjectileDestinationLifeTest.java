package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectileDestinationLifeTest {
    static JsonArray body(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do"); }
    static JsonObject destination(JsonObject data) { return body(data).get(3).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("projectile").getAsJsonObject("destination"); }
    static final EffectSource SOURCE = new EffectSource("source", "test:destination_life", "player", new BuffInstance.Origin("player", "source", "", "test:seeker"), Set.of());
    static final EntityAnchor.Reference FIRST = new EntityAnchor.Reference("enemy", "first-life", "world");
    static final class Harness {
        final CompiledEffects program; EffectSession session; EntityAnchor.Reference current = FIRST; boolean missing, failLaunch;
        final List<ProjectileFlight.Launch> launches = new ArrayList<>(); int observations;
        Harness() throws Exception { this(json("projectile_destination_life")); }
        Harness(JsonObject data) {
            program = compile(data); session = new EffectSession(program, EffectCheckpointTest.clock(), 1, EffectState.empty().withSource(SOURCE), this::world);
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            return switch (request.command()) {
                case EntityAnchor.Query q -> { observations++; yield new EntityAnchor.Result(q, missing ? EntityAnchor.Outcome.MISSING : EntityAnchor.Outcome.AVAILABLE,
                        missing ? Optional.empty() : Optional.of(new EntityAnchor.Observation(current, new WorldPosition(current.dimension(), 4, 43, 1), q.anchor(), true))); }
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(new WorldPosition("world", 1, 40, 1)));
                case ProjectileFlight.Launch launch -> { launches.add(launch); if (failLaunch) throw new IllegalStateException("Unknown accepted seeker launch"); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("flight-" + launches.size())); }
                default -> throw new AssertionError(request.command());
            };
        }
        void scan() { session.start(session.state().engine().timeMicros(), new RuleEngine.Signal("test:destination_launch", new EffectEvent("player", "enemy", SOURCE.origin(), Set.of(), Map.of()))); }
        void until(long time) { session.observe(time, List.of()); }
        void restore() { session = EffectSession.restore(program, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())), this::world); }
    }
    @Test void delayedLaunchRetainsTheOriginallyObservedLifeWithoutLookingUpAReplacement() throws Exception {
        var h = new Harness(); h.scan(); h.current = new EntityAnchor.Reference("enemy", "replacement", "world");
        h.session.start(0, SourceChange.remove("source")); h.until(999_999); assertTrue(h.launches.isEmpty()); h.until(1_000_000);
        assertEquals(1, h.observations); var launch = h.launches.getFirst(); var destination = launch.parameters().destination().orElseThrow();
        assertEquals(Optional.of(FIRST), destination.reference()); assertEquals("enemy", destination.target()); assertEquals(SOURCE.origin(), launch.origin());
        assertEquals(720, destination.turnRate()); assertEquals(.1, destination.arrivalRadius()); assertTrue(destination.catching().isPresent());
    }
    @Test void independentScansCaptureIndependentLivesAndSurviveDomainCheckpoint() throws Exception {
        var h = new Harness(); h.scan(); var second = new EntityAnchor.Reference("enemy", "second-life", "world"); h.current = second; h.scan(); h.restore(); h.until(1_000_000);
        assertEquals(List.of(FIRST, second), h.launches.stream().map(p -> p.parameters().destination().orElseThrow().reference().orElseThrow()).toList());
        assertEquals(2, h.observations); assertTrue(h.session.state().idle());
        var codec = EffectCheckpointCodec.DATA;
        for (var launch : h.launches) assertEquals(launch, codec.decode(codec.encode(launch), ProjectileFlight.Launch.class));
        assertEquals(h.program.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.program.program()).getOrThrow()).getOrThrow());
    }
    @Test void legacyDeclarationRemainsIdentityOnlyInsteadOfSilentlyCapturingALife() throws Exception {
        var data = json("projectile_destination_life"); destination(data).remove("reference"); var h = new Harness(data); h.scan(); h.until(1_000_000);
        assertTrue(h.launches.getFirst().parameters().destination().orElseThrow().reference().isEmpty());
    }
    @Test void lifeBindingRequiresAnEntityObservationInTheCurrentLexicalScope() throws Exception {
        for (String ref : List.of("unbound", "muzzle", "aim", "contact")) {
            var data = json("projectile_destination_life"); destination(data).addProperty("reference", ref);
            assertThrows(RuntimeException.class, () -> compile(data), ref);
        }
        var typo = json("projectile_destination_life"); destination(typo).addProperty("life", "chosen_life");
        assertThrows(RuntimeException.class, () -> compile(typo));
    }
    @Test void unavailableOrDifferentTargetObservationCannotTurnIntoAnUnguardedLaunch() throws Exception {
        var h = new Harness(); h.missing = true; h.scan(); assertTrue(assertThrows(IllegalStateException.class, () -> h.until(1_000_000)).getMessage().contains("Entity anchor unavailable")); assertTrue(h.launches.isEmpty());
        var data = json("projectile_destination_life"); destination(data).addProperty("target", "self"); var foreign = new Harness(data); foreign.scan();
        assertTrue(assertThrows(IllegalStateException.class, () -> foreign.until(1_000_000)).getMessage().contains("destination differs from captured life")); assertTrue(foreign.launches.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ProjectileDestination("other", 1, 1, TargetQuery.Anchor.BODY, false, Optional.empty(), Optional.of(FIRST)));
    }
    @Test void unknownSpawnNeverReplaysOrRetargetsTheAcceptedLaunch() throws Exception {
        var h = new Harness(); h.scan(); h.failLaunch = true; assertThrows(IllegalStateException.class, () -> h.until(1_000_000));
        h.current = new EntityAnchor.Reference("enemy", "newer", "world"); assertThrows(IllegalStateException.class, () -> h.until(2_000_000));
        assertEquals(1, h.launches.size()); assertEquals(Optional.of(FIRST), h.launches.getFirst().parameters().destination().orElseThrow().reference());
    }
    @Test void retiredDestinationRecordsMigrateWithoutInventingPreviouslyUnobservedLife() {
        var codec = EffectCheckpointCodec.DATA;
        var runtime = new ProjectileDestination("enemy", 720, .1, TargetQuery.Anchor.EYES, false, Optional.of(new ProjectileCatch(5, 0, 2_000_000, false)));
        var declaration = new ProjectileSpec.Destination(Evaluation.Target.VICTIM, new Value.Constant(720, ProjectileSpec.TURN_RATE), new Value.Constant(.1, Unit.METER), TargetQuery.Anchor.EYES, false,
                Optional.of(new ProjectileSpec.Catch(new Value.Constant(5, Unit.METER), new Value.Constant(0, Unit.SECOND), new Value.Constant(2, Unit.SECOND), false)));
        for (var item : List.of(runtime, declaration)) {
            var wire = codec.encode(item).getAsJsonObject(); String currentId = wire.get("$").getAsString(); assertTrue(currentId.endsWith("_v2"));
            String oldId = currentId.substring(0, currentId.length() - 3); wire.addProperty("$", oldId);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(wire, item.getClass()), "new field under retired ID must not be silently discarded");
            wire.getAsJsonObject("v").remove("reference"); assertEquals(item, codec.decode(wire, item.getClass()));
            assertEquals(currentId, codec.encode(codec.decode(wire, item.getClass())).getAsJsonObject().get("$").getAsString());
        }
    }
    static int downgradeIdentityOnlyDestinations(JsonElement tree) {
        int changed = 0;
        if (tree.isJsonArray()) for (var child : tree.getAsJsonArray()) changed += downgradeIdentityOnlyDestinations(child);
        else if (tree.isJsonObject()) {
            var object = tree.getAsJsonObject(); String kind = object.has("$") ? object.get("$").getAsString() : "";
            if (kind.equals("chorus:effect/data/projectile_spec/destination_v2") || kind.equals("chorus:effect/projectile/projectile_destination_v2")) {
                object.addProperty("$", kind.substring(0, kind.length() - 3)); object.getAsJsonObject("v").remove("reference"); changed++;
            }
            for (var field : object.entrySet()) changed += downgradeIdentityOnlyDestinations(field.getValue());
        }
        return changed;
    }
    @Test void wholeRetiredCheckpointResumesPendingIdentityOnlyLaunchWithItsPinnedCatalogue() throws Exception {
        var data = json("projectile_destination_life"); destination(data).remove("reference"); var h = new Harness(data); h.scan();
        var old = EffectCheckpointCodec.encode(h.session.checkpoint()); assertTrue(downgradeIdentityOnlyDestinations(old) > 0);
        h.session = EffectSession.restore(h.program, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(old), h::world);
        h.until(1_000_000); assertTrue(h.launches.getFirst().parameters().destination().orElseThrow().reference().isEmpty());
        assertEquals(h.program.program(), h.session.checkpoint().program()); assertEquals(1, h.observations);
    }
}
