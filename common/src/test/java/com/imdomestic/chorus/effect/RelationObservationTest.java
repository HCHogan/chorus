package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.RelationQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class RelationObservationTest {
    static CompiledEffects program() {
        return compile(JsonParser.parseString("""
            {"version":"test-relation","bundles":[{"id":"test:relation","rules":[
              {"id":"probe","on":"test:probe","do":[
                {"action":{"type":"chorus:inspect_relation"},"as":"relation"},
                {"if":{"type":"chorus:result_flag","binding":"relation","field":"not_allied"},
                 "then":[{"type":"chorus:play_cue","cue":"test:enemy"}]}
              ]}
            ]}]}
            """).getAsJsonObject());
    }
    @Test void onlyAnObservedNonAllyEntersTheEnemyBranchAndDuplicateReceiptDoesNotReplayIt() {
        var engine = engine(program()); var source = source("test:relation");
        var waiting = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:probe", event(source));
        var q = (RelationQuery) waiting.actions().getFirst().command(); assertEquals(new RelationQuery("player", "target"), q);
        for (var allied : List.of(Optional.<Boolean>empty(), Optional.of(true), Optional.of(false))) {
            var receipt = new RelationQuery.Result(q, allied); var next = complete(engine, waiting, receipt);
            assertEquals(allied.isPresent(), ResultShape.RELATION.flag("available", receipt));
            assertEquals(allied.isEmpty(), ResultShape.RELATION.flag("missing", receipt));
            assertEquals(allied.orElse(false), ResultShape.RELATION.flag("allied", receipt));
            if (allied.equals(Optional.of(false))) {
                assertEquals("test:enemy", ((Action.CueCommand) next.actions().getFirst().command()).cue());
                assertEquals(next.state(), engine.transition(next.state(), new RuleEngine.Completed(waiting.actions().getFirst().id(), receipt)).state());
            } else { assertTrue(next.actions().isEmpty()); assertTrue(next.state().idle()); }
        }
    }
    @Test void receiptForDifferentIdentitiesCannotAuthorizeAnEnemyAction() {
        var engine = engine(program()); var source = source("test:relation");
        var waiting = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:probe", event(source));
        for (var q : List.of(new RelationQuery("other", "target"), new RelationQuery("player", "other"), new RelationQuery("target", "player"))) {
            var failed = engine.transition(waiting.state(), new RuleEngine.Completed(waiting.actions().getFirst().id(), new RelationQuery.Result(q, Optional.of(false))));
            assertTrue(failed.state().engine().failure().isPresent()); assertTrue(failed.actions().isEmpty());
        }
    }
}
