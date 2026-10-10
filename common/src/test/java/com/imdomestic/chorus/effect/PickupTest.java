package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class PickupTest {
    private static final WorldPosition POINT = new WorldPosition("world", 1, 40, 3);
    private static final EffectSource SOURCE = source("test:pickup");
    private static JsonArray actions(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do"); }
    static final class Harness {
        final EffectSession session;
        final List<WorldPickup.Spawn> spawns = new ArrayList<>();
        final List<HealingCommand> heals = new ArrayList<>(); final List<Action.CueCommand> cues = new ArrayList<>();
        Harness() throws Exception {
            var p = load("pickup");
            var collector = new EffectSource("collector", "test:collector", "target", SOURCE.origin(), Set.of());
            var producer = new EffectSource("producer-listener", "test:collector", "player", SOURCE.origin(), Set.of());
            session = new EffectSession(engine(p), EffectState.empty().withSource(SOURCE).withSource(collector).withSource(producer), request -> switch (request.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(POINT));
                case WorldPickup.Spawn spawn -> { spawns.add(spawn); yield new WorldPickup.Receipt(spawn, WorldPickup.Outcome.SPAWNED, Optional.of("entity" + spawns.size())); }
                case HealingCommand heal -> { heals.add(heal); yield HealingReceipt.unapplied(request.id().toString(), heal, HealingReceipt.Outcome.MISSING); }
                case Action.CueCommand cue -> { cues.add(cue); yield RuleEngine.Empty.INSTANCE; }
                default -> throw new AssertionError(request.command());
            });
        }
        void spawn() { session.start(session.state().engine().timeMicros(), new RuleEngine.Signal("test:spawn", event(SOURCE))); assertTrue(session.state().idle(), () -> session.state().engine().failure().toString()); }
        void finish(int index, WorldPickup.Contact contact) { session.start(contact.ageMicros(), spawns.get(index).finish(contact)); assertTrue(session.state().idle(), () -> session.state().engine().failure().toString()); }
    }
    private static WorldPickup.Contact collected(String who, long age) { return new WorldPickup.Contact(WorldPickup.End.COLLECTED, POINT, Optional.of(who), age); }
    @Test void detachedCollectionKeepsCapturedValuesAndProducerWhileOnlyCollectorPerksReact() throws Exception {
        var h = new Harness(); h.spawn(); assertTrue(h.heals.isEmpty() && h.cues.isEmpty());
        h.session.start(0, SourceChange.remove("perk"));
        h.finish(0, collected("target", 50_000));
        assertEquals(1, h.heals.size()); assertEquals("target", h.heals.getFirst().target()); assertEquals(7, h.heals.getFirst().amount());
        assertEquals(SOURCE.origin(), h.heals.getFirst().source());
        assertEquals(List.of("target"), h.cues.stream().map(Action.CueCommand::target).toList());
        var fact = (EffectEvent) collected("target", 50_000).fact("id", "test:pickup", SOURCE.origin()).payload();
        assertEquals("target", fact.actor()); assertEquals("target", fact.victim()); assertEquals(SOURCE.origin(), fact.source());
        assertEquals("id", fact.references().get("pickup_id")); assertEquals(1, fact.numbers().get("count").value());
    }
    @Test void expiryHasPositionButNoCollectorOrPickupFactAndSeparateUnitsKeepTheirIdentity() throws Exception {
        var h = new Harness(); h.spawn(); h.spawn();
        assertNotEquals(h.spawns.get(0).continuation().id(), h.spawns.get(1).continuation().id());
        var expired = new WorldPickup.Contact(WorldPickup.End.EXPIRED, POINT, Optional.empty(), 2_000_000);
        h.finish(1, expired); assertTrue(h.heals.isEmpty()); assertEquals("test:expired", h.cues.getFirst().cue());
        assertEquals(Optional.of(POINT), ResultShape.PICKUP_CONTACT.position(expired)); assertTrue(ResultShape.PICKUP_CONTACT.targets(expired).isEmpty());
        assertEquals(2, ResultShape.PICKUP_CONTACT.read("age", expired).value());
    }
    @Test void collectorLifetimeDimensionAndReceiptContradictionsAreRejected() throws Exception {
        var h = new Harness(); h.spawn(); var spawn = h.spawns.getFirst();
        assertThrows(IllegalArgumentException.class, () -> spawn.finish(collected("other", 50_000)));
        assertThrows(IllegalArgumentException.class, () -> spawn.finish(collected("target", 2_000_000)));
        assertThrows(IllegalArgumentException.class, () -> spawn.finish(new WorldPickup.Contact(WorldPickup.End.EXPIRED, POINT, Optional.empty(), 1)));
        assertThrows(IllegalArgumentException.class, () -> spawn.finish(new WorldPickup.Contact(WorldPickup.End.COLLECTED, new WorldPosition("other", 1, 2, 3), Optional.of("target"), 1)));
        assertThrows(IllegalArgumentException.class, () -> new WorldPickup.Contact(WorldPickup.End.EXPIRED, POINT, Optional.of("target"), 1));
        assertThrows(IllegalArgumentException.class, () -> new WorldPickup.Receipt(spawn, WorldPickup.Outcome.SPAWNED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new WorldPickup.Parameters(0, 1, Optional.empty()));
    }
    @Test void codecsCheckLexicalTypesUnitsAndAttractionProfiles() throws Exception {
        var program = load("pickup").program();
        assertEquals(program, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow());
        for (String fault : List.of("unit", "position", "recipient", "shadow", "negative", "escape", "profile", "speed", "lifetime")) {
            var data = json("pickup"); var steps = actions(data); var pickup = steps.get(2).getAsJsonObject(); var spec = pickup.getAsJsonObject("pickup");
            switch (fault) {
                case "unit" -> spec.getAsJsonObject("radius").addProperty("unit", "damage");
                case "position" -> spec.addProperty("position", "captured");
                case "recipient" -> spec.add("recipient", JsonParser.parseString("{\"binding\":\"place\"}"));
                case "shadow" -> pickup.addProperty("as", "place");
                case "negative" -> spec.getAsJsonObject("radius").addProperty("value", -1);
                case "escape" -> steps.add(JsonParser.parseString("{\"for_each\":\"contact\",\"as\":\"t\",\"do\":[]}"));
                case "profile" -> spec.getAsJsonObject("attraction").addProperty("profile", "test:amount");
                case "speed" -> spec.getAsJsonObject("attraction").getAsJsonObject("speed").addProperty("value", -1);
                case "lifetime" -> spec.getAsJsonObject("lifetime").addProperty("value", 0);
            }
            assertThrows(RuntimeException.class, () -> compile(data), fault);
        }
    }
    @Test void matchingSpawnReceiptIsRequiredAndDuplicateCompletionDoesNotSpawnAgain() throws Exception {
        var engine = engine(load("pickup")); var waiting = send(engine, engine.initial(EffectState.empty().withSource(SOURCE)), 0, "test:spawn", event(SOURCE));
        waiting = complete(engine, waiting, new PositionQuery.Result((PositionQuery) waiting.actions().getFirst().command(), Optional.of(POINT)));
        var operation = waiting.actions().getFirst(); var spawn = (WorldPickup.Spawn) operation.command();
        var receipt = new WorldPickup.Receipt(spawn, WorldPickup.Outcome.SPAWNED, Optional.of("entity"));
        var settled = complete(engine, waiting, receipt); var duplicate = engine.transition(settled.state(), new RuleEngine.Completed(operation.id(), receipt));
        assertEquals(settled.state(), duplicate.state()); assertTrue(duplicate.actions().isEmpty());
        var other = new WorldPickup.Spawn(spawn.position(), spawn.kind(), "other", spawn.origin(), spawn.parameters(), spawn.continuation(), spawn.contactSlot());
        var invalid = engine.transition(waiting.state(), new RuleEngine.Completed(operation.id(), new WorldPickup.Receipt(other, WorldPickup.Outcome.SPAWNED, Optional.of("entity"))));
        while (invalid.needsPump()) invalid = engine.transition(invalid.state(), RuleEngine.Pump.INSTANCE);
        assertTrue(invalid.state().engine().failure().isPresent()); assertTrue(invalid.actions().isEmpty());
        var rejected = complete(engine, waiting, new WorldPickup.Receipt(spawn, WorldPickup.Outcome.REJECTED, Optional.empty()));
        assertTrue(rejected.state().idle()); assertTrue(rejected.actions().isEmpty());
    }
}
