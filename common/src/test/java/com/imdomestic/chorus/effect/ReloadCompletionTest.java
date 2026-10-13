package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReloadCompletionTest {
    static WeaponReloadTest.Harness harness(boolean incremental) throws Exception {
        var data = incremental ? IncrementalReloadTest.data() : json("weapons");
        data.getAsJsonArray("buffs").add(JsonParser.parseString("""
            {"definition":{"id":"test:reload_receipts","version":"test-1","duration":60,
              "components":{"numbers":{"seen":{"initial":0,"unit":"count"}},"sets":["completions"]}}}
            """));
        data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules").add(JsonParser.parseString("""
            {"id":"record_completion","on":"chorus:reload_finished","if":{"type":"chorus:source_is","source":"this_weapon"},"do":[
              {"if":{"type":"chorus:not","of":{"type":"chorus:has_buff","buff":"test:reload_receipts"}},"then":[{"type":"chorus:grant_buff","buff":"test:reload_receipts"}]},
              {"type":"chorus:update_component","buff":"test:reload_receipts","component":"seen","op":"add","value":{"type":"chorus:constant","value":1,"unit":"count"},"once_set":"completions","event_reference":"reload_completion_id"}]}
            """));
        var p = CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(), load("kill_clip").program()));
        var h = new WeaponReloadTest.Harness(p, EffectState.empty()); h.equip(WeaponReloadTest.pair("primary")); return h;
    }
    static Set<String> completions(WeaponReloadTest.Harness h) {
        return h.state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("test:reload_receipts"))
                .findFirst().map(b -> b.components().sets().get("completions")).orElse(Set.of());
    }
    @Test void eachInsertionHasAnIndependentIdentityAndReplayingItsTimerDoesNotRepeatIt() throws Exception {
        var h = harness(true); var first = h.reload().plan().orElseThrow();
        h.until(200_000); var next = h.state().reloads().get("player");
        assertEquals(Set.of(first.completionId()), completions(h)); assertEquals(first.token(), next.token());
        assertNotEquals(first.completionId(), next.completionId()); assertEquals(first.completionId(), first.between().completionId());
        h.session.start(h.now(), new RuleEngine.Signal(WeaponReload.DUE, first)); assertEquals(1, completions(h).size());
        h.until(300_000); assertEquals(Set.of(first.completionId(), next.completionId()), completions(h));
        h.until(1_000_000); assertEquals(4, completions(h).size()); assertEquals(4, h.heals.size());
    }
    @Test void laterCompletedReloadCanReuseTheRequestTokenWithoutReusingItsCompletion() throws Exception {
        var h = harness(false); var first = h.reload().plan().orElseThrow(); h.until(200_000);
        h.event("test:spend", "a", 1); h.session.start(h.now(), new WeaponReload.Request("player", first.token()).signal());
        var second = h.state().reloads().get("player"); assertNotEquals(first.completionId(), second.completionId());
        h.until(400_000); assertEquals(Set.of(first.completionId(), second.completionId()), completions(h));
    }
    @Test void rejectedOrRefilledReloadDoesNotIssueACompletionReceipt() throws Exception {
        var h = harness(false); h.reload(); h.allowed = false; h.until(200_000); assertTrue(completions(h).isEmpty());
        h.allowed = true; h.reload(); h.event("test:refill", "a", 0); h.until(400_000); assertTrue(completions(h).isEmpty());
    }
    @Test void instantCompletionsSeparateWeaponsAndOperationsEvenWithZeroTransfer() throws Exception {
        var h = new InstantReloadTest.Harness(); h.equip(WeaponReloadTest.pair("primary"));
        var q = h.check(InstantReload.Selection.EQUIPPED, InstantReload.Completion.VERIFIED);
        var result = h.p.completeInstantReload(h.state(), new InstantReload.Checked(q, true));
        var facts = result.emitted().stream().filter(e -> e.type().equals("chorus:reload_finished"))
                .map(e -> ((InstantReload.Completed)e.payload()).event()).toList();
        assertEquals(2, facts.size()); assertEquals(Set.of(q.completionId("a"), q.completionId("b")),
                new HashSet<>(facts.stream().map(e -> e.references().get("reload_completion_id")).toList()));
        assertTrue(facts.stream().allMatch(e -> q.token().equals(e.references().get("reload"))));
        var fresh = new InstantReload.Check(q.holder(), q.equipment(), q.selection(), q.completion(), q.reason(), q.cause(), new RuleEngine.OperationId(101, 0, 0));
        var empty = h.p.completeInstantReload(result.state(), new InstantReload.Checked(fresh, true));
        assertEquals(0, ((InstantReload.Result)empty.result()).applied());
        var zeroIds = empty.emitted().stream().filter(e -> e.type().equals("chorus:reload_finished"))
                .map(e -> ((InstantReload.Completed)e.payload()).event().references().get("reload_completion_id")).toList();
        assertEquals(Set.of(fresh.completionId("a"), fresh.completionId("b")), new HashSet<>(zeroIds));
        assertFalse(zeroIds.contains(q.completionId("a"))); assertFalse(zeroIds.contains(q.completionId("b")));
    }
}
