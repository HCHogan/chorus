package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class IncrementalReloadTest {
    static JsonObject data() throws Exception {
        var data = json("weapons"); var weapon = data.getAsJsonArray("weapons").get(0).getAsJsonObject(); var settings = json("incremental_reload_settings");
        weapon.getAsJsonObject("reload").add("insert", settings.get("insert")); weapon.add("fire", settings.get("fire")); return data;
    }
    static CompiledEffects compileData(JsonObject data) throws Exception {
        return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(), load("kill_clip").program()));
    }
    static WeaponReloadTest.Harness harness(JsonObject data) throws Exception {
        var h = new WeaponReloadTest.Harness(compileData(data), EffectState.empty()); h.equip(WeaponReloadTest.pair("primary")); return h;
    }
    static JsonObject insert(JsonObject data) { return data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("reload").getAsJsonObject("insert"); }
    @Test void eachInsertionCommitsAndTriggersReloadPerksBeforeSchedulingTheNextStep() throws Exception {
        var h = harness(data()); h.event("chorus:kill", "a", 0); var first = h.reload().plan().orElseThrow();
        h.until(199_999); assertEquals(1, h.ammo("a").magazine()); h.until(200_000);
        assertEquals(2, h.ammo("a").magazine()); assertEquals(11, h.ammo("a").reserve().orElseThrow().rounds());
        assertTrue(h.active("chorus_d2:kill_clip", "a")); assertEquals(1, h.heals.size());
        assertEquals(WeaponReload.Phase.BETWEEN_INSERTS, h.atHeal.getFirst().reloads().get("player").phase());
        var next = h.state().reloads().get("player"); assertEquals(1, next.step()); assertEquals(300_000, next.dueAt());
        assertNotEquals(first.timerId(), next.timerId());
        var beforeReplay = h.state();
        h.session.start(h.now(), new RuleEngine.Signal(WeaponReload.DUE, first));
        h.session.start(h.now(), new RuleEngine.Signal(WeaponReload.NEXT, h.atHeal.getFirst().reloads().get("player")));
        assertEquals(beforeReplay, h.state()); assertEquals(1, h.verifications.size());
        h.until(1_000_000); assertEquals(5, h.ammo("a").magazine()); assertEquals(8, h.ammo("a").reserve().orElseThrow().rounds());
        assertEquals(4, h.heals.size()); assertEquals(4, h.verifications.size());
        assertTrue(h.state().reloads().isEmpty() && h.state().timers().values().stream().noneMatch(t -> t.signal().type().equals(WeaponReload.DUE)));
        assertTrue(h.session.state().idle() && h.session.state().engine().failure().isEmpty());
    }
    @Test void acceptedFireRetainsLoadedRoundsAndCancelsOnlyTheRemainingInsertions() throws Exception {
        var h = harness(data()); h.reload(); h.until(250_000);
        h.session.start(h.now(), new WeaponFire.Request("player", "shot").signal());
        assertEquals(1, h.ammo("a").magazine()); assertEquals(11, h.ammo("a").reserve().orElseThrow().rounds());
        h.until(1_000_000); assertEquals(1, h.heals.size()); assertEquals(1, h.ammo("a").magazine()); assertTrue(h.state().reloads().isEmpty());
    }
    @Test void emptyFireDoesNotCancelAndStowRedrawCannotResumeTheOldStep() throws Exception {
        var data = data(); data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition").addProperty("magazine", 0);
        var h = harness(data); var old = h.reload().plan().orElseThrow();
        var denied = h.program.fire(h.state(), new WeaponFire.Request("player", "empty")); assertEquals(WeaponFire.Outcome.NO_AMMUNITION, ((WeaponFire.Receipt) denied.result()).outcome()); assertEquals(h.state(), denied.state());
        h.until(200_000); h.equip(WeaponReloadTest.pair("secondary")); h.equip(WeaponReloadTest.pair("primary"));
        h.session.start(h.now(), new RuleEngine.Signal(WeaponReload.DUE, old));
        h.until(400_000); assertEquals(1, h.ammo("a").magazine()); assertEquals(1, h.heals.size()); assertTrue(h.state().reloads().isEmpty());
        var fresh = h.reload().plan().orElseThrow(); assertEquals(0, fresh.step()); assertEquals(600_000, fresh.dueAt());
    }
    @Test void currentCapacityAndPartialReservesClipEachInsertionAndUnlimitedReservesStillStopAtFull() throws Exception {
        for (boolean unlimited : List.of(false, true)) {
            var d = data(); insert(d).getAsJsonObject("rounds").addProperty("value", 3);
            var ammo = d.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition");
            if (unlimited) ammo.addProperty("reserves", "unlimited"); else ammo.getAsJsonObject("reserves").addProperty("rounds", 2);
            var h = harness(d); h.reload(); h.until(1_000_000);
            assertEquals(unlimited ? 5 : 3, h.ammo("a").magazine()); assertEquals(unlimited ? List.of(3.,1.) : List.of(2.), h.heals.stream().map(x -> x.amount()).toList());
            assertTrue(h.state().reloads().isEmpty());
            if (!unlimited) assertEquals(0, h.ammo("a").reserve().orElseThrow().rounds());
        }
        var h = harness(data()); h.reload(); h.until(100_000); h.event("test:expand", "a", 0); h.until(300_000);
        // Capacity contribution expires exactly before the third insertion is planned.
        assertEquals(3, h.ammo("a").magazine()); h.until(1_000_000); assertEquals(5, h.ammo("a").magazine());
    }
    @Test void everyStepPinsItsDurationButTheNextOneReadsCurrentModifiers() throws Exception {
        var h = harness(data()); h.source(true); var first = h.reload().plan().orElseThrow(); assertEquals(100_000, first.dueAt());
        h.source(false); h.until(100_000); assertEquals(200_000, h.state().reloads().get("player").dueAt());
        h.source(true); h.until(200_000); assertEquals(250_000, h.state().reloads().get("player").dueAt());
        h.until(300_000); assertEquals(5, h.ammo("a").magazine()); assertTrue(h.state().reloads().isEmpty());
    }
    @Test void completedReloadReactionChangesBothNextInsertionCountAndTiming() throws Exception {
        var d = data(); insert(d).addProperty("rounds_profile", "test:insertion_rounds");
        d.getAsJsonArray("profiles").add(JsonParser.parseString("""
            {"id":"test:insertion_rounds","version":"test-1","input_unit":"round","steps":[
              {"type":"chorus:apply","id":"rounds","operation":"add","group":{"name":"rounds","reduction":"sum"}}]}
            """));
        d.getAsJsonArray("buffs").add(JsonParser.parseString("""
            {"definition":{"id":"test:insertion_bonus","version":"test-1","duration":10,"attach":"holder","instanced_by":"weapon","affects":"instance_weapon","on_stow":"keep"},"bundle":"test:insertion_bonus"}
            """));
        d.getAsJsonArray("bundles").add(JsonParser.parseString("""
            {"id":"test:insertion_bonus","scope":"buff","modifiers":[
              {"id":"rounds","profile":"test:insertion_rounds","stage":"rounds","group":"rounds","op":"add","stacking_key":"test:bonus","value":{"type":"chorus:constant","value":1,"unit":"round"},"reference":"Synthetic insertion count","confidence":"assumed"},
              {"id":"time","profile":"test:reload_time","stage":"time","group":"time","op":"multiply","stacking_key":"test:bonus","value":{"type":"chorus:constant","value":-0.5,"unit":"delta"},"reference":"Synthetic insertion time","confidence":"assumed"}]}
            """));
        d.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules").add(JsonParser.parseString("""
            {"id":"bonus","on":"chorus:reload_finished","if":{"type":"chorus:source_is","source":"this_weapon"},"do":[{"type":"chorus:grant_buff","buff":"test:insertion_bonus"}]}
            """));
        var h = harness(d); h.reload(); h.until(200_000);
        var next = h.state().reloads().get("player"); assertEquals(250_000, next.dueAt()); assertEquals(2, next.portion().orElseThrow().rounds());
        assertEquals(2, next.portion().orElseThrow().calculation().orElseThrow().output().value());
        h.until(300_000); assertEquals(List.of(1.,2.,1.), h.heals.stream().map(x -> x.amount()).toList()); assertEquals(5, h.ammo("a").magazine());
    }
    @Test void rejectedHostAndUnknownCompletionReactionNeverUndoOrReplayCommittedRounds() throws Exception {
        var h = harness(data()); h.reload(); h.until(200_000); h.allowed = false; h.until(300_000);
        assertEquals(2, h.ammo("a").magazine()); assertEquals(11, h.ammo("a").reserve().orElseThrow().rounds()); assertEquals(1, h.heals.size()); assertTrue(h.state().reloads().isEmpty());
        var failed = harness(data()); failed.failHeal = true; failed.reload(); assertThrows(IllegalStateException.class, () -> failed.until(200_000));
        assertEquals(2, failed.ammo("a").magazine()); assertEquals(11, failed.ammo("a").reserve().orElseThrow().rounds());
        assertEquals(WeaponReload.Phase.BETWEEN_INSERTS, failed.state().reloads().get("player").phase());
        assertThrows(IllegalStateException.class, () -> failed.until(300_000)); assertEquals(1, failed.heals.size());
    }
    @Test void invalidNextStepCalculationKeepsTheCompletedAmmoTransferAndStopsTheSeries() throws Exception {
        var d = data(); insert(d).getAsJsonObject("repeat").add("value", JsonParser.parseString("""
            {"type":"chorus:choose","if":{"type":"chorus:compare","left":{"type":"chorus:event_number","name":"reload_step","unit":"count"},"op":"eq","right":{"type":"chorus:constant","value":0,"unit":"count"}},
             "then":{"type":"chorus:constant","value":0.1,"unit":"second"},"else":{"type":"chorus:constant","value":-1,"unit":"second"}}
            """));
        var h = harness(d); h.reload(); assertThrows(IllegalStateException.class, () -> h.until(200_000));
        assertTrue(h.session.state().engine().failure().isPresent()); assertEquals(2, h.ammo("a").magazine()); assertEquals(1, h.heals.size());
        assertEquals(WeaponReload.Phase.BETWEEN_INSERTS, h.state().reloads().get("player").phase());
    }
    @Test void reloadInsertRoundtripsAndRejectsFractionalCountsZeroPeriodsAndIncompatibleProfiles() throws Exception {
        var p = compileData(data()); assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        for (String variant : List.of("fraction", "zero", "unit", "timing", "profile", "typo")) {
            var d = data(); var insert = insert(d);
            switch (variant) {
                case "fraction" -> insert.getAsJsonObject("rounds").addProperty("value",1.5);
                case "zero" -> insert.getAsJsonObject("rounds").addProperty("value",0);
                case "unit" -> insert.getAsJsonObject("rounds").addProperty("unit","count");
                case "timing" -> { insert.getAsJsonObject("repeat").remove("profile"); insert.getAsJsonObject("repeat").getAsJsonObject("value").addProperty("value",0); }
                case "profile" -> insert.addProperty("rounds_profile","test:reload_time");
                case "typo" -> insert.addProperty("round",2);
                default -> throw new AssertionError();
            }
            assertThrows(RuntimeException.class, () -> compileData(d),variant);
        }
    }
}
