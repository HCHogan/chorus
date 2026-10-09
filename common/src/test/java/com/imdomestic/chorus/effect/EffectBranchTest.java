package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.data.Action.CueCommand;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EffectBranchTest {
    private static final ResourceState.Key ENERGY = new ResourceState.Key("player", "test:energy");

    @Test void branchDecisionAndLexicalResultSurviveWorldWaitAfterItsConditionChanges() throws Exception {
        for (boolean takeThen : List.of(true, false)) {
            var data = json("branches");
            if (!takeThen) data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject()
                    .getAsJsonArray("do").get(1).getAsJsonObject().add("if", JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":false}"));
            var compiled = compile(data); var engine = engine(compiled); var source = source("test:branch");
            var initial = engine.initial(EffectState.empty().withSource(source).withResource(new ResourceState(ENERGY, 0, 1, 0)));
            var waiting = send(engine, initial, 0, "test:choose", event(source));
            assertEquals(takeThen ? "test:then" : "test:else", ((CueCommand) waiting.actions().getFirst().command()).cue());
            assertEquals(takeThen ? 2 : 7, buff(waiting.state(), "test:counter", "player").count());
            assertEquals(0, waiting.state().engine().domain().resources().get(ENERGY).value());
            if (takeThen) assertTrue(waiting.state().engine().domain().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("test:gate")));
            var afterBranch = complete(engine, waiting, Empty.INSTANCE);
            assertEquals("test:end", ((CueCommand) afterBranch.actions().getFirst().command()).cue());
            assertEquals(takeThen ? .2 : .7, afterBranch.state().engine().domain().resources().get(ENERGY).value(), 1e-12);
            var finished = complete(engine, afterBranch, Empty.INSTANCE);
            assertTrue(finished.state().idle());
            var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, compiled).getOrThrow();
            assertEquals(compiled.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        }
    }

    @Test void branchBindingsCannotEscapeToOuterScopeOrOppositeBranchOrShadowOuterResults() throws Exception {
        for (String mutation : List.of("escape", "sibling", "shadow", "unknown_flag")) {
            var data = json("branches");
            var actions = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do");
            var branch = actions.get(1).getAsJsonObject();
            var energy = branch.getAsJsonArray("else").get(2).deepCopy();
            switch (mutation) {
                case "escape" -> actions.add(energy);
                case "sibling" -> branch.getAsJsonArray("else").set(0, energy);
                case "shadow" -> actions.set(0, JsonParser.parseString("{\"action\":{\"type\":\"chorus:grant_buff\",\"buff\":\"test:gate\"},\"as\":\"gain\"}"));
                case "unknown_flag" -> branch.getAsJsonArray("then").get(3).getAsJsonObject().getAsJsonObject("if").addProperty("field", "missing");
            }
            assertTrue(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).error().isPresent(), mutation);
        }
    }

    @Test void damageWaitBindsActualResultAndDefersDeathReactionsUntilTheCurrentFrameFinishes() {
        var data = JsonParser.parseString("""
                {"version":"test-1","buffs":[
                  {"definition":{"id":"test:kills","version":"test-1","duration":"permanent","max_stacks":10}},
                  {"definition":{"id":"test:deaths","version":"test-1","duration":"permanent","max_stacks":10}},
                  {"definition":{"id":"test:saved","version":"test-1","duration":"permanent","max_stacks":10}}
                ],"bundles":[{"id":"test:damage","rules":[
                  {"id":"damage","on":"test:attack","do":[
                    {"action":{"type":"chorus:damage","amount":{"type":"chorus:constant","value":10000,"unit":"damage"},
                      "damage_type":"test:physical","kill_tags":["chorus:weapon_kill"]},"as":"damage"},
                    {"if":{"type":"chorus:result_flag","binding":"damage","field":"lethal"},
                      "then":[{"type":"chorus:play_cue","cue":"test:lethal"}],
                      "else":[{"if":{"type":"chorus:result_flag","binding":"damage","field":"death_prevented"},
                        "then":[{"type":"chorus:play_cue","cue":"test:saved"}],
                        "else":[{"type":"chorus:play_cue","cue":"test:survived"}]}]}
                  ]},
                  {"id":"kill","on":"chorus:kill","do":[{"type":"chorus:grant_buff","buff":"test:kills"}]},
                  {"id":"death","on":"chorus:death","do":[{"type":"chorus:grant_buff","buff":"test:deaths","target":"victim"}]},
                  {"id":"saved","on":"chorus:death_prevented","do":[{"type":"chorus:grant_buff","buff":"test:saved","target":"victim"}]}
                ]}]}
                """).getAsJsonObject();
        var compiled = compile(data); var engine = engine(compiled); var source = source("test:damage");
        for (boolean lethal : List.of(true, false)) {
            var waiting = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:attack", event(source));
            assertEquals(10000, ((DamageCommand) waiting.actions().getFirst().command()).amount());
            assertTrue(waiting.state().engine().domain().buffs().instances().isEmpty());
            var receipt = new DamageReceipt("damage", DamageReceipt.Outcome.APPLIED, 0, 4, 6, lethal ? Optional.of("death") : Optional.empty(), !lethal);
            var resultBranch = complete(engine, waiting, receipt);
            assertEquals(lethal ? "test:lethal" : "test:saved", ((CueCommand) resultBranch.actions().getFirst().command()).cue());
            assertTrue(resultBranch.state().engine().domain().buffs().instances().isEmpty());
            assertEquals(resultBranch.state(), engine.transition(resultBranch.state(), new Completed(waiting.actions().getFirst().id(), receipt)).state());
            var finished = complete(engine, resultBranch, Empty.INSTANCE);
            if (lethal) {
                assertEquals(1, buff(finished.state(), "test:kills", "player").count());
                assertEquals(1, buff(finished.state(), "test:deaths", "target").count());
                assertEquals(2, finished.state().engine().domain().buffs().instances().size());
            } else {
                assertEquals(1, buff(finished.state(), "test:saved", "target").count());
                assertEquals(1, finished.state().engine().domain().buffs().instances().size());
            }
        }
    }
}
