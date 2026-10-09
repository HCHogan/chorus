package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.BuffsTest.*;
import static com.imdomestic.chorus.effect.buff.BuffDefinition.*;
import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuffRulesTest {
    private record Recorded(String weapon, int count) implements WorldCommand {}
    private static Instruction<BuffStore> action(Action<BuffStore> action) { return new Instruction<>(action, ""); }
    private static EventRule<BuffStore> rule(String id, String event, List<Instruction<BuffStore>> actions) {
        return new EventRule<>(id, event, (_, _) -> true, actions);
    }
    private static Local<BuffStore> local(Buffs.Result result) { return new Local<>(result.store(), result.receipt(), result.signals()); }
    private static Transition<BuffStore> settle(RuleEngine<BuffStore> engine, Transition<BuffStore> transition) {
        for (int i = 0; i < 1000 && transition.needsPump(); i++) transition = engine.transition(transition.state(), Pump.INSTANCE);
        assertFalse(transition.needsPump());
        assertTrue(transition.state().failure().isEmpty(), transitionFailure(transition));
        return transition;
    }
    private static String transitionFailure(Transition<BuffStore> transition) { return transition.state().failure().toString(); }

    @Test void expiredSourceRunsItsOwnEndRuleBeforeAFollowingExternalCommand() {
        var active = definition("test:active", 1, SECOND, TimerMode.SHARED, Decay.ALL, Refresh.NONE, OnStow.KEEP, false, false);
        var cooldown = definition("test:cooldown", 1, 2 * SECOND, TimerMode.SHARED, Decay.ALL, Refresh.NONE, OnStow.KEEP, false, false);
        var onEnd = new EventRule<BuffStore>("active-end", "chorus:buff_ended",
                (_, context) -> ((BuffRules.Scope) context.scope()).owns((Buffs.Change) context.event().signal().payload()),
                List.of(action((store, context) -> {
                    var finalInstance = ((BuffRules.Scope) context.scope()).current(store).orElseThrow();
                    assertEquals(SECOND, context.event().timeMicros());
                    assertEquals(7, finalInstance.components().numbers().get("history"));
                    assertTrue(store.active(finalInstance.key()).isEmpty());
                    return local(grant(store, cooldown, finalInstance.origin(), 1));
                })));
        var sources = new BuffRules<>(Map.of(active, List.of(onEnd)), java.util.function.Function.identity());
        var advance = rule("advance", "test:advance", List.of(action((store, context) -> {
            var result = Buffs.advanceStep(store, context.event().timeMicros());
            if (result.store().timeMicros() != context.event().timeMicros()) throw new IllegalArgumentException("Host skipped a deadline");
            return local(result);
        })));
        var attack = new EventRule<BuffStore>("attack", "test:attack", (store, _) -> store.active(key(cooldown, A)).isEmpty(),
                List.of(action((_, _) -> new Await<>(new Recorded("weapon-a", 1)))));
        var definitions = new ArrayList<>(sources.definitions()); definitions.add(advance); definitions.add(attack);
        RuleResolver<BuffStore> resolver = (store, event) -> {
            var bindings = new ArrayList<>(sources.resolve(store, event));
            if (event.signal().type().equals("test:advance")) bindings.add(new RuleBinding("advance", "advance", Empty.INSTANCE));
            if (event.signal().type().equals("test:attack")) bindings.add(new RuleBinding("attack", "attack", Empty.INSTANCE));
            return bindings;
        };
        var engine = new RuleEngine<>("1", definitions, 1, resolver);
        var store = grant(BuffStore.empty(), active, A, 1).store();
        store = Buffs.components(store, key(active, A), BuffComponents.EMPTY.number("history", BuffComponents.Update.SET, 7));
        var expired = settle(engine, engine.transition(engine.initial(store), new Start(SECOND, new Signal("test:advance", Empty.INSTANCE))));
        assertTrue(expired.state().domain().active(key(cooldown, A)).isPresent());
        assertEquals(3 * SECOND, expired.state().domain().nextDeadline());
        var blocked = settle(engine, engine.transition(expired.state(), new Start(SECOND, new Signal("test:attack", Empty.INSTANCE))));
        assertTrue(blocked.actions().isEmpty());
        var endCooldown = settle(engine, engine.transition(blocked.state(), new Start(3 * SECOND, new Signal("test:advance", Empty.INSTANCE))));
        var allowed = settle(engine, engine.transition(endCooldown.state(), new Start(3 * SECOND, new Signal("test:attack", Empty.INSTANCE))));
        assertEquals(new Recorded("weapon-a", 1), allowed.actions().getFirst().command());
    }

    @Test void sameTemplateBindsDifferentWeaponsAndResultFrameSurvivesSourceRemoval() {
        var active = definition("test:one_shot", 1, FOREVER, TimerMode.SHARED, Decay.ALL, Refresh.NONE, OnStow.KEEP, false, false);
        var consumeAndReport = rule("consume-and-report", "hit", List.of(
                new Instruction<>((store, context) -> local(Buffs.consume(store, ((BuffRules.Scope) context.scope()).snapshot().key(), 1)), "spent"),
                action((_, context) -> new Await<>(new Recorded(((BuffRules.Scope) context.scope()).snapshot().origin().weapon(),
                        -context.result("spent", Buffs.Receipt.class).storedDelta()))),
                action((store, context) -> {
                    assertTrue(((BuffRules.Scope) context.scope()).current(store).isEmpty());
                    return new Local<>(store, Empty.INSTANCE, List.of());
                })));
        var unavailable = rule("unavailable", "hit", List.of(action((_, _) -> { throw new IllegalStateException("Removed source must not start another rule"); })));
        var sources = new BuffRules<>(Map.of(active, List.of(consumeAndReport, unavailable)), java.util.function.Function.identity());
        var engine = new RuleEngine<>("1", sources.definitions(), 1, sources);
        var store = grant(BuffStore.empty(), active, A, 1).store();
        store = grant(store, active, B, 1).store();
        var first = settle(engine, engine.transition(engine.initial(store), new Start(0, new Signal("hit", Empty.INSTANCE))));
        assertEquals(new Recorded("weapon-a", 1), first.actions().getFirst().command());
        var second = settle(engine, engine.transition(first.state(), new Completed(first.actions().getFirst().id(), Empty.INSTANCE)));
        assertEquals(new Recorded("weapon-b", 1), second.actions().getFirst().command());
        assertNotEquals(first.actions().getFirst().id(), second.actions().getFirst().id());
        var done = settle(engine, engine.transition(second.state(), new Completed(second.actions().getFirst().id(), Empty.INSTANCE)));
        assertTrue(done.state().idle());
        assertTrue(done.state().domain().instances().isEmpty());
    }

    @Test void fullStackRefreshReadsCurrentComponentsAndRunsExactlyOnce() {
        var active = definition("test:refresh", 1, 5 * SECOND, TimerMode.SHARED, Decay.ALL, Refresh.RESET, OnStow.KEEP, false, false);
        var react = new EventRule<BuffStore>("on-refresh", "chorus:buff_refreshed",
                (_, context) -> ((BuffRules.Scope) context.scope()).owns((Buffs.Change) context.event().signal().payload()),
                List.of(action((store, context) -> {
                    var instance = ((BuffRules.Scope) context.scope()).current(store).orElseThrow();
                    return new Local<>(Buffs.components(store, instance.key(), instance.components().number("refreshes", BuffComponents.Update.ADD, 1)), Empty.INSTANCE, List.of());
                })));
        var sources = new BuffRules<>(Map.of(active, List.of(react)), java.util.function.Function.identity());
        var engine = new RuleEngine<>("1", sources.definitions(), 2, sources);
        var store = grant(BuffStore.empty(), active, A, 1).store();
        var refreshed = grant(store, active, A, 1);
        var signal = refreshed.signals().stream().filter(value -> value.type().equals("chorus:buff_refreshed")).findFirst().orElseThrow();
        var result = settle(engine, engine.transition(engine.initial(refreshed.store()), new Start(0, signal)));
        assertEquals(1, instance(result.state().domain(), active, A).components().numbers().get("refreshes"));
    }
}
