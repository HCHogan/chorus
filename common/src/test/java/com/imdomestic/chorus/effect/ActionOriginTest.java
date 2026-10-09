package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ActionOriginTest {
    private static final BuffInstance.Origin APPLIER = new BuffInstance.Origin("applier", "grenade-A", "", "ability-A", Set.of("test:applier"));
    private static final BuffInstance.Origin TRIGGER = new BuffInstance.Origin("trigger", "shot-B", "weapon-B", "", Set.of("test:trigger"));
    private static EffectSource source(String id, String bundle, BuffInstance.Origin origin) { return new EffectSource(id, bundle, origin.owner(), origin, Set.of()); }
    private static EffectEvent input(BuffInstance.Origin origin) {
        return new EffectEvent(origin.owner(), "carrier", origin, Set.of("chorus:weapon_damage", "chorus:weapon_kill"), Map.of());
    }
    private static EffectState initial(CompiledEffects program, long duration) {
        var state = EffectState.empty().withSource(source("applier-power", "test:power", APPLIER)).withSource(source("trigger-power", "test:power", TRIGGER))
                .withSource(source("applier-observer", "test:credit_observer", APPLIER)).withSource(source("trigger-observer", "test:credit_observer", TRIGGER));
        return state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:carrier"), "carrier", "carrier", APPLIER, 1, 1, duration).store());
    }
    private static JsonArray rules(JsonObject json) { return json.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules"); }
    private static JsonObject damage(JsonObject json) { return rules(json).get(0).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action"); }
    private static JsonObject heal(JsonObject json) { return rules(json).get(0).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonArray("then").get(0).getAsJsonObject(); }
    private static JsonObject capture(JsonObject json) { return rules(json).get(1).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action"); }
    private static final class Harness {
        final CompiledEffects program;
        final EffectSession session;
        final List<RuleEngine.WorldRequest> requests = new ArrayList<>();
        final List<Double> output = new ArrayList<>();
        boolean lethal;
        Harness(CompiledEffects program, long duration) {
            this.program = program;
            session = new EffectSession(engine(program), initial(program, duration), request -> {
                requests.add(request);
                return switch (request.command()) {
                    case TargetQuery query -> new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, List.of(new TargetQuery.Target("neighbor", 1)));
                    case DamageCommand command -> {
                        double amount = program.outgoing(state(), command, command.amount()).map(r -> r.output().value()).orElse(command.amount());
                        output.add(amount);
                        yield new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, lethal ? 1 : amount, lethal ? Optional.of("death-" + request.id()) : Optional.empty(), false);
                    }
                    case HealingCommand command -> new HealingReceipt(request.id().toString(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
                    default -> throw new AssertionError(request.command());
                };
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        void fire(long time, String type, BuffInstance.Origin origin) {
            session.start(time, new RuleEngine.Signal(type, input(origin))); assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty());
        }
        List<DamageCommand> damages() { return requests.stream().map(RuleEngine.WorldRequest::command).filter(DamageCommand.class::isInstance).map(DamageCommand.class::cast).toList(); }
        List<HealingCommand> heals() { return requests.stream().map(RuleEngine.WorldRequest::command).filter(HealingCommand.class::isInstance).map(HealingCommand.class::cast).toList(); }
        boolean has(String buff, String holder) { return state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals(buff) && b.key().holder().equals(holder)); }
    }
    @Test void otherAttackerOwnsDerivedDamageAndHealingWithoutRebindingCarrierOrCopyingWeaponCredit() throws Exception {
        var h = new Harness(load("action_origin"), 10_000_000); h.lethal = true; h.fire(0, "test:release", TRIGGER);
        var command = h.damages().getFirst(); assertEquals(TRIGGER, command.source()); assertEquals(4, command.amount());
        assertEquals(List.of(6d), h.output, "Queries use trigger's 50%, not applier's 100%");
        assertEquals(Set.of("test:chain"), command.tags()); assertEquals(Set.of("test:chain_kill"), command.killTags());
        var query = (TargetQuery) h.requests.getFirst().command(); assertEquals(new TargetQuery.EntityCenter("carrier"), query.center());
        assertEquals("trigger", query.relativeTo()); assertEquals(Set.of("trigger"), query.exclude());
        assertEquals(TRIGGER, h.heals().getFirst().source()); assertEquals("trigger", h.heals().getFirst().target()); assertEquals(.5, h.heals().getFirst().amount());
        assertTrue(h.has("test:chain_kill", "trigger")); assertFalse(h.has("test:chain_kill", "applier"));
        assertTrue(h.has("test:heal_credit", "trigger")); assertFalse(h.has("test:heal_credit", "applier"));
        assertFalse(h.has("test:weapon_kill", "trigger")); assertFalse(h.has("test:weapon_kill", "applier"));
        assertEquals(APPLIER, buff(h.session.state(), "test:carrier", "carrier").origin());
    }
    @Test void eachTriggerChoosesItsOwnEventOriginWhileOmissionAndExplicitBoundKeepOldSemantics() throws Exception {
        var h = new Harness(load("action_origin"), 10_000_000);
        h.fire(0, "test:release", TRIGGER); h.fire(1, "test:release", APPLIER);
        assertEquals(List.of(TRIGGER, APPLIER), h.damages().stream().map(DamageCommand::source).toList()); assertEquals(List.of(6d, 8d), h.output);
        for (boolean explicit : List.of(false, true)) {
            var data = json("action_origin");
            for (var action : List.of(damage(data), heal(data), capture(data))) {
                if (explicit) action.addProperty("origin", "bound"); else action.remove("origin");
            }
            var bound = new Harness(compile(data), 10_000_000); bound.fire(0, "test:release", TRIGGER);
            assertEquals(APPLIER, bound.damages().getFirst().source()); assertEquals(List.of(8d), bound.output);
            assertEquals(APPLIER, bound.heals().getFirst().source()); assertEquals("trigger", bound.heals().getFirst().target(), "Origin selection does not rewrite target");
            bound.fire(0, "test:deferred", TRIGGER); bound.session.observe(100_000, List.of());
            assertEquals(APPLIER, bound.damages().getLast().source());
        }
    }
    @Test void eventOriginSnapshotFreezesTriggerModifiersAndSurvivesCarrierExpiryAndSourceRemoval() throws Exception {
        var h = new Harness(load("action_origin"), 50_000); h.fire(0, "test:deferred", TRIGGER);
        h.session.start(0, SourceChange.remove("trigger-power")); h.session.start(0, SourceChange.remove("applier-power"));
        h.session.observe(100_000, List.of()); assertTrue(h.session.state().idle()); assertTrue(h.session.state().engine().failure().isEmpty());
        assertFalse(h.has("test:carrier", "carrier"));
        assertEquals(TRIGGER, h.damages().getFirst().source()); assertEquals(List.of(6d), h.output);
        var snapshot = h.damages().getFirst().snapshot().orElseThrow(); assertEquals(TRIGGER, snapshot.attack().source());
        assertEquals(Set.of("test:chain"), snapshot.attack().tags());
    }
    @Test void timerAndNestedDelayedActionsRetainTheOriginalEventOrigin() throws Exception {
        var data = json("action_origin");
        var delayed = rules(data).get(1).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonArray("do");
        delayed.add(JsonParser.parseString("""
                {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"lifetime":"detached","do":[
                 {"type":"chorus:heal","origin":"event","target":"event_actor","amount":{"type":"chorus:constant","value":2,"unit":"damage"}}]}
                """));
        var h = new Harness(compile(data), 10_000_000);
        h.fire(0, "test:schedule", TRIGGER); h.fire(0, "test:deferred", TRIGGER);
        h.fire(50_000, "test:unrelated", APPLIER); h.session.observe(200_000, List.of());
        assertEquals(2, h.damages().size()); assertTrue(h.damages().stream().allMatch(c -> c.source().equals(TRIGGER)));
        assertEquals(TRIGGER, h.heals().getFirst().source()); assertEquals("trigger", h.heals().getFirst().target());
        assertTrue(h.session.state().engine().failure().isEmpty());
    }
    @Test void eventOriginUsesTheFullSourceRatherThanActorAndPreservesUnownedEnvironmentalDamage() {
        var bound = source("bound", "test:any", APPLIER);
        var amount = new Value.Constant(4, Unit.DAMAGE);
        var action = new Action.Damage(Evaluation.Target.VICTIM, amount, "minecraft:generic", Set.of("test:chain"), Set.of(), false, Optional.empty(), Map.of(), ActionOrigin.EVENT);
        for (var origin : List.of(TRIGGER, new BuffInstance.Origin("", "environment", "", ""))) {
            var event = new RuleEngine.Event(1, 1, Optional.empty(), 0, new RuleEngine.Signal("test:hit", new EffectEvent("unrelated-actor", "target", origin, Set.of("chorus:weapon_damage"), Map.of())));
            var e = new Evaluation(EffectState.empty(), new RuleEngine.Context(event, "bound", bound, Map.of()), Map.of(), Map.of());
            var command = (DamageCommand) ((RuleEngine.Await<EffectState>) action.execute(e)).command();
            assertEquals(origin, command.source()); assertEquals(APPLIER, e.origin());
            var facts = DamageFacts.from(command, new DamageReceipt("hit", DamageReceipt.Outcome.APPLIED, 0, 0, 1, Optional.of("death"), false));
            assertEquals(origin.owner(), ((EffectEvent) facts.getFirst().payload()).actor());
            assertEquals(!origin.owner().isEmpty(), facts.stream().anyMatch(f -> f.type().equals("chorus:kill")));
        }
        var noEvent = new Evaluation(EffectState.empty(), new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), 0,
                new RuleEngine.Signal("test:missing", RuleEngine.Empty.INSTANCE)), "bound", bound, Map.of()), Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> action.execute(noEvent), "Missing source cannot fall back to the bound applier");
    }
    @Test void originCodecRoundTripsAndRejectsUnsupportedSelectionOrSnapshotReattribution() throws Exception {
        var program = load("action_origin");
        assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program.program()).getOrThrow()).getOrThrow().program());
        for (String kind : List.of("damage", "heal", "capture")) {
            var data = json("action_origin");
            var action = switch (kind) { case "damage" -> damage(data); case "heal" -> heal(data); default -> capture(data); };
            action.addProperty("origin", "current_weapon"); assertThrows(RuntimeException.class, () -> compile(data));
        }
        var data = json("action_origin"); rules(data).get(1).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().addProperty("origin", "event");
        assertThrows(RuntimeException.class, () -> compile(data), "A captured attack keeps its original provenance");
    }
}
