package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.EntityQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Unit;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EntityObservationTest {
    private static final EffectSource SOURCE = source("test:observe");
    @Test void observationExposesTypedHealthAndPlayerFactsWithoutConfusingDeadAndMissing() {
        var query = new EntityQuery("target"); var alive = new EntityQuery.Result(query, Optional.of(new EntityQuery.View(true, true, 8, 20, 3)));
        assertTrue(ResultShape.ENTITY.flag("available", alive)); assertTrue(ResultShape.ENTITY.flag("alive", alive)); assertTrue(ResultShape.ENTITY.flag("player", alive));
        assertEquals(8, ResultShape.ENTITY.read("health", alive).value()); assertEquals(Unit.DAMAGE, ResultShape.ENTITY.unit("health"));
        assertEquals(20, ResultShape.ENTITY.read("max_health", alive).value()); assertEquals(3, ResultShape.ENTITY.read("absorption", alive).value());
        assertEquals(.4, ResultShape.ENTITY.read("health_fraction", alive).value()); assertEquals(Unit.MULTIPLIER, ResultShape.ENTITY.unit("health_fraction"));
        var dead = new EntityQuery.Result(query, Optional.of(new EntityQuery.View(false, false, 0, 20, 0)));
        assertTrue(ResultShape.ENTITY.flag("available", dead)); assertFalse(ResultShape.ENTITY.flag("alive", dead)); assertFalse(ResultShape.ENTITY.flag("player", dead));
        var missing = new EntityQuery.Result(query, Optional.empty()); assertTrue(ResultShape.ENTITY.flag("missing", missing));
        assertThrows(IllegalStateException.class, () -> ResultShape.ENTITY.read("health", missing));
        assertThrows(IllegalStateException.class, () -> ResultShape.ENTITY.flag("alive", missing));
        assertThrows(IllegalStateException.class, () -> ResultShape.ENTITY.flag("player", missing));
    }
    @Test void matchingObservationResumesOnceAndAnUnavailableOrDeadEntitySkipsHealing() throws Exception {
        var engine = engine(load("entity_observation")); var state = engine.initial(EffectState.empty().withSource(SOURCE));
        var waiting = send(engine, state, 0, "test:probe", event(SOURCE)); var query = (EntityQuery) waiting.actions().getFirst().command(); assertEquals("target", query.target());
        var observation = new EntityQuery.Result(query, Optional.of(new EntityQuery.View(true, false, 8, 20, 0)));
        var healing = complete(engine, waiting, observation); var command = (HealingCommand) healing.actions().getFirst().command(); assertEquals(12, command.amount());
        assertEquals(healing.state(), engine.transition(healing.state(), new RuleEngine.Completed(waiting.actions().getFirst().id(), observation)).state());
        assertTrue(complete(engine, healing, new HealingReceipt("heal", command, HealingReceipt.Outcome.APPLIED, 12, 12, 0)).state().idle());
        for (var view : List.of(Optional.<EntityQuery.View>empty(), Optional.of(new EntityQuery.View(false, false, 0, 20, 0)), Optional.of(new EntityQuery.View(true, true, 20, 20, 4)))) {
            assertTrue(complete(engine, waiting, new EntityQuery.Result(query, view)).state().idle());
        }
    }
    @Test void wrongTargetReceiptCannotInfluenceTheRuleAndDuplicateConflictsAreRejected() throws Exception {
        var engine = engine(load("entity_observation")); var waiting = send(engine, engine.initial(EffectState.empty().withSource(SOURCE)), 0, "test:probe", event(SOURCE));
        var view = Optional.of(new EntityQuery.View(true, false, 8, 20, 0)); var op = waiting.actions().getFirst().id();
        var failed = engine.transition(waiting.state(), new RuleEngine.Completed(op, new EntityQuery.Result(new EntityQuery("wrong"), view)));
        assertTrue(failed.state().engine().failure().isPresent()); assertTrue(failed.actions().isEmpty());
        var good = new EntityQuery.Result(new EntityQuery("target"), view); var healing = complete(engine, waiting, good);
        assertThrows(IllegalArgumentException.class, () -> engine.transition(healing.state(), new RuleEngine.Completed(op, new EntityQuery.Result(new EntityQuery("target"), Optional.empty()))));
    }
    @Test void observationBindingSurvivesDelayedExecutionAndDoesNotBecomeATargetReference() throws Exception {
        var data = json("entity_observation"); var actions = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do");
        var conditional = actions.remove(1); var after = JsonParser.parseString("""
                {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"lifetime":"detached","do":[]}
                """).getAsJsonObject(); after.getAsJsonArray("do").add(conditional); actions.add(after);
        var program = compile(data); var engine = engine(program);
        var waiting = send(engine, engine.initial(EffectState.empty().withSource(SOURCE)), 0, "test:probe", event(SOURCE));
        var scheduled = complete(engine, waiting, new EntityQuery.Result(new EntityQuery("target"), Optional.of(new EntityQuery.View(true, false, 8, 20, 0))));
        var unbound = pump(engine, engine.transition(scheduled.state(), new RuleEngine.Start(0, SourceChange.remove(SOURCE.instance()))));
        var late = pump(engine, engine.transition(unbound.state(), new RuleEngine.Start(100_000, List.of())));
        assertEquals(12, ((HealingCommand) late.actions().getFirst().command()).amount());
        var invalid = json("entity_observation"); invalid.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").add(JsonParser.parseString("""
                {"type":"chorus:capture_position","target":{"binding":"before"}}
                """));
        assertThrows(RuntimeException.class, () -> compile(invalid));
    }
    @Test void strictCodecUnitsAndFiniteObservationValuesAreValidated() throws Exception {
        var program = load("entity_observation"); assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program.program()).getOrThrow()).getOrThrow().program());
        var data = json("entity_observation"); var body = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do");
        body.get(1).getAsJsonObject().getAsJsonObject("if").getAsJsonArray("of").get(2).getAsJsonObject().getAsJsonObject("right").addProperty("unit", "damage");
        assertThrows(RuntimeException.class, () -> compile(data));
        assertThrows(IllegalArgumentException.class, () -> ResultShape.ENTITY.requireFlag("guardian"));
        assertThrows(IllegalArgumentException.class, () -> new EntityQuery(" "));
        for (double invalid : List.of(-1d, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> new EntityQuery.View(true, false, invalid, 20, 0));
            assertThrows(IllegalArgumentException.class, () -> new EntityQuery.View(true, false, 1, invalid, 0));
            assertThrows(IllegalArgumentException.class, () -> new EntityQuery.View(true, false, 1, 20, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new EntityQuery.View(true, false, 0, 0, 0));
    }
}
