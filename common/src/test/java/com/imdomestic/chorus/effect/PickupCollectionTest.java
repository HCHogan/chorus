package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.object.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class PickupCollectionTest {
    static void addCollection(JsonObject data) {
        for (var entry : data.getAsJsonArray("bundles")) if (entry.getAsJsonObject().get("id").getAsString().equals("test:collector"))
            entry.getAsJsonObject().getAsJsonArray("rules").add(JsonParser.parseString("""
                {"id":"remote","on":"test:collect","if":{"type":"chorus:target_is","left":"self","right":"event_actor"},"do":[
                  {"type":"chorus:collect_pickups","collector":"self","radius":{"type":"chorus:constant","value":5,"unit":"meter"},"kinds":["test:pickup"],"line_of_sight":false}
                ]}
                """));
    }
    static final class Harness {
        final CompiledEffects program; final EffectSession session;
        final EffectSource producer = source("test:pickup");
        final List<PickupCollection.Entry> entries = new ArrayList<>(); final Set<String> consumed = new HashSet<>();
        final List<String> order = new ArrayList<>(); final List<HealingCommand> heals = new ArrayList<>();
        boolean failHealing, mismatched;
        Harness(boolean conditional) throws Exception {
            var data = json(conditional ? "conditional_pickup" : "pickup"); addCollection(data); program = compile(data);
            var state = EffectState.empty().withSource(producer).withSource(new EffectSource("collector", "test:collector", "target", producer.origin(), Set.of()));
            if (conditional) {
                state = ConditionalPickupTest.equip(program, state, "target", new Loadout(Map.of("test:primary", ConditionalPickupTest.gear("a", "finite")), Optional.empty()));
                state = state.withAmmo(state.ammunition().get("a").reserve(7));
            }
            session = new EffectSession(engine(program), state, this::apply);
        }
        RuleEngine.ActionResult apply(RuleEngine.WorldRequest request) {
            return switch (request.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(new WorldPosition("world", 0, 0, 0)));
                case WorldPickup.Spawn spawn -> {
                    String id = "pickup" + entries.size(); entries.add(new PickupCollection.Entry(id, spawn));
                    yield new WorldPickup.Receipt(spawn, WorldPickup.Outcome.SPAWNED, Optional.of(id));
                }
                case PickupCollection.Query query -> new PickupCollection.Result(query, PickupCollection.Outcome.FOUND, List.copyOf(entries));
                case PickupCollection.Claim claim -> {
                    order.add("claim/" + claim.entry().entity());
                    var outcome = consumed.contains(claim.entry().entity()) ? PickupCollection.ClaimOutcome.UNAVAILABLE
                            : !program.canCollectPickup(session.state().engine().domain(), claim.entry().spawn()) ? PickupCollection.ClaimOutcome.INELIGIBLE : PickupCollection.ClaimOutcome.COLLECTED;
                    if (outcome == PickupCollection.ClaimOutcome.COLLECTED) consumed.add(claim.entry().entity());
                    var actual = mismatched ? new PickupCollection.Claim(new PickupCollection.Query("target", 9, claim.query().kinds(), false), claim.entry()) : claim;
                    yield new PickupCollection.Claimed(actual, outcome, outcome == PickupCollection.ClaimOutcome.COLLECTED
                            ? Optional.of(new WorldPickup.Contact(WorldPickup.End.COLLECTED, claim.entry().spawn().position().orElseThrow(), Optional.of("target"), 0)) : Optional.empty());
                }
                case HealingCommand heal -> { order.add("reward"); heals.add(heal); if (failHealing) throw new IllegalStateException("Unknown healing write"); yield HealingReceipt.unapplied(request.id().toString(), heal, HealingReceipt.Outcome.MISSING); }
                case Action.CueCommand ignored -> { order.add("pickup_fact"); yield RuleEngine.Empty.INSTANCE; }
                default -> throw new AssertionError(request.command());
            };
        }
        void spawn() { session.start(0, new RuleEngine.Signal("test:spawn", event(producer))); }
        void collect() { session.start(0, new RuleEngine.Signal("test:collect", new EffectEvent("target", "target", producer.origin(), Set.of(), Map.of()))); }
    }
    @Test void eachClaimCompletesItsOriginalRewardAndPickupFactBeforeAdvancingTheBatch() throws Exception {
        var h = new Harness(false); h.spawn(); h.spawn(); h.session.start(0, SourceChange.remove("perk")); h.collect();
        assertEquals(List.of("claim/pickup0", "reward", "pickup_fact", "claim/pickup1", "reward", "pickup_fact"), h.order);
        assertEquals(2, h.heals.size()); assertTrue(h.heals.stream().allMatch(x -> x.target().equals("target") && x.amount() == 7 && x.source().equals(h.producer.origin())));
        h.collect(); assertEquals(2, h.heals.size()); assertTrue(h.session.state().idle());
    }
    @Test void laterClaimsReadTheFirstRewardsUpdatedReserveCapacity() throws Exception {
        var h = new Harness(true); h.spawn(); h.spawn(); h.collect();
        assertEquals(1, h.consumed.size()); assertEquals(1, h.heals.size());
        assertEquals(10, h.session.state().engine().domain().ammunition().get("a").reserve().orElseThrow().rounds());
        assertEquals(List.of("claim/pickup0", "reward", "pickup_fact", "claim/pickup1"), h.order);
    }
    @Test void unknownRewardStopsRemainingClaimsAndRetainsAlreadyCommittedDomainChanges() throws Exception {
        var h = new Harness(true); h.spawn(); h.spawn(); h.failHealing = true;
        assertThrows(IllegalStateException.class, h::collect);
        assertEquals(Set.of("pickup0"), h.consumed); assertEquals(List.of("claim/pickup0", "reward"), h.order);
        assertEquals(10, h.session.state().engine().domain().ammunition().get("a").reserve().orElseThrow().rounds());
        assertThrows(IllegalStateException.class, h::collect); assertEquals(1, h.heals.size());
    }
    @Test void aMismatchedWorldClaimCannotResumeAnUnrelatedReward() throws Exception {
        var h = new Harness(false); h.spawn(); h.mismatched = true;
        assertThrows(IllegalStateException.class, h::collect); assertTrue(h.heals.isEmpty()); assertEquals(1, h.consumed.size());
    }
    @Test void candidateAndContactContractsRejectDuplicatePrivateOrContradictoryData() throws Exception {
        var h = new Harness(false); h.spawn(); var e = h.entries.getFirst(); var q = new PickupCollection.Query("target", 5, Set.of("test:pickup"), false);
        assertThrows(IllegalArgumentException.class, () -> new PickupCollection.Result(q, PickupCollection.Outcome.FOUND, List.of(e,e)));
        assertThrows(IllegalArgumentException.class, () -> new PickupCollection.Batch(new PickupCollection.Query("other", 5, q.kinds(), false), List.of(e)));
        assertThrows(IllegalArgumentException.class, () -> new PickupCollection.Batch(new PickupCollection.Query("target", 5, Set.of("test:other"), false), List.of(e)));
        assertThrows(IllegalArgumentException.class, () -> new PickupCollection.Result(q, PickupCollection.Outcome.MISSING_COLLECTOR, List.of(e)));
        assertThrows(IllegalArgumentException.class, () -> new PickupCollection.Claimed(new PickupCollection.Claim(q,e), PickupCollection.ClaimOutcome.COLLECTED, Optional.empty()));
    }
    @Test void programsAndQueuedBatchesRoundTripWithCapturedSourceAndLexicalBindings() throws Exception {
        var h = new Harness(false); h.spawn(); h.spawn(); var program = h.program.program();
        assertEquals(program, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow());
        assertEquals(program, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(program), EffectProgram.class));
        var batch = new PickupCollection.Batch(new PickupCollection.Query("target", 5, Set.of("test:pickup"), false), h.entries);
        assertEquals(batch, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(batch), PickupCollection.Batch.class));
        assertEquals(batch.scope(), EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(batch.scope()), EffectContinuations.Pending.class));
        var event = new RuleEngine.Event(42, 1, Optional.of(30L), 90, batch.signal());
        assertEquals(event, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(event), RuleEngine.Event.class));
    }
    @Test void invalidUnitsNegativeRadiusAndEmptyKindsFailBeforeWorldExecution() throws Exception {
        for (String fault : List.of("unit", "radius", "kinds", "unknown")) {
            var data=json("pickup");addCollection(data);
            var collector=data.getAsJsonArray("bundles").get(1).getAsJsonObject();
            var rules=collector.getAsJsonArray("rules"); var action=rules.get(rules.size()-1).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject();
            switch(fault) {
                case "unit" -> action.getAsJsonObject("radius").addProperty("unit","damage");
                case "radius" -> action.getAsJsonObject("radius").addProperty("value",-1);
                case "kinds" -> action.add("kinds",new JsonArray());
                case "unknown" -> action.addProperty("owner","other");
            }
            assertThrows(RuntimeException.class,()->compile(data),fault);
        }
    }
}
