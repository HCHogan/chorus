package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.compile;
import static com.imdomestic.chorus.test.ResourceRuntimeGameTest.domain;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.EffectContinuations;
import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.EffectSource;
import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.buff.BuffRules;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.data.DamageSnapshot;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;

/** A historical attack keeps its source clock while the physical hit and live modifiers use the arrival world. */
public class DamageSnapshotTransferGameTest {
    private static CompiledEffects program(boolean buffOwned) {
        var data = NativeCharacterTransferGameTest.data();
        data.add("profiles", JsonParser.parseString("""
            [{"id":"test:transfer_damage","version":"native-character-transfer","input_unit":"damage",
              "steps":[{"type":"chorus:apply","id":"bonus","operation":"multiply","group":{"name":"bonus","reduction":"sum"}}]}]
            """));
        for (String timing : List.of("on_use", "on_hit")) {
            var buff = JsonParser.parseString("""
                {"definition":{"id":"test:TIMING","version":"native-character-transfer","duration":"permanent"},"bundle":"test:TIMING"}
                """.replace("TIMING", timing));
            data.getAsJsonArray("buffs").add(buff);
            data.getAsJsonArray("bundles").add(JsonParser.parseString("""
                {"id":"test:TIMING","scope":"buff","modifiers":[
                  {"id":"bonus","profile":"test:transfer_damage","stage":"bonus","group":"bonus","op":"multiply",
                   "stacking_key":"test:TIMING","evaluate":"TIMING","value":{"type":"chorus:constant","value":0.5,"unit":"delta"},
                   "reference":"Synthetic snapshot clock transfer fixture","confidence":"assumed"}]}
                """.replace("TIMING", timing)));
        }
        var body = JsonParser.parseString("""
            [{"action":{"type":"chorus:capture_damage","amount":{"type":"chorus:constant","value":3,"unit":"damage"},
               "damage_type":"minecraft:generic","scaling_profile":"test:transfer_damage"},"as":"attack"},
             {"type":"chorus:remove_buff","buff":"test:on_use"},
             {"after":{"type":"chorus:constant","value":0.2,"unit":"second"},"do":[
               {"type":"chorus:damage_snapshot","snapshot":"attack","target":"self"},
               {"type":"chorus:remove_buff","buff":"test:on_hit"},
               {"type":"chorus:play_cue","cue":"test:snapshot_first"},
               {"after":{"type":"chorus:constant","value":0.6,"unit":"second"},"do":[
                 {"type":"chorus:damage_snapshot","snapshot":"attack","target":"self"},
                 {"type":"chorus:play_cue","cue":"test:snapshot_second"}
               ]}
             ]}]
            """).getAsJsonArray();
        var actorRules = data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules");
        var start = JsonParser.parseString("""
            {"id":"snapshot","on":"test:snapshot","do":[{"type":"chorus:grant_buff","buff":"test:on_use"}]}
            """).getAsJsonObject();
        if (buffOwned) {
            data.getAsJsonArray("buffs").add(JsonParser.parseString("""
                {"definition":{"id":"test:snapshot_carrier","version":"native-character-transfer","duration":"permanent"},"bundle":"test:snapshot_carrier"}
                """));
            start.getAsJsonArray("do").add(JsonParser.parseString("""
                {"type":"chorus:grant_buff","buff":"test:snapshot_carrier"}
                """));
            var carrier = JsonParser.parseString("""
                {"id":"test:snapshot_carrier","scope":"buff","rules":[
                  {"id":"snapshot","on":"chorus:buff_gained","if":{"type":"chorus:own_buff"},"do":[]}]}
                """).getAsJsonObject();
            carrier.getAsJsonArray("rules").get(0).getAsJsonObject().add("do", body);
            data.getAsJsonArray("bundles").add(carrier);
        } else {
            for (var step : body) start.getAsJsonArray("do").add(step);
        }
        actorRules.add(start);
        actorRules.add(JsonParser.parseString("""
            {"id":"live","on":"test:snapshot_live","do":[{"type":"chorus:grant_buff","buff":"test:on_hit"}]}
            """));
        actorRules.add(JsonParser.parseString("""
            {"id":"hit","on":"chorus:hit","binding":"origin_bundle","do":[{"type":"chorus:play_cue","cue":"test:snapshot_hit"}]}
            """));
        return compile(data);
    }
    private static boolean hasBuff(EffectState state, String holder, String definition) {
        return state.buffs().instances().values().stream()
                .anyMatch(buff -> buff.key().holder().equals(holder) && buff.definition().id().equals(definition));
    }
    private static List<EffectContinuations.Pending> continuations(EffectState state) {
        return state.timers().values().stream().map(timer -> timer.signal().payload())
                .filter(EffectContinuations.Pending.class::isInstance).map(EffectContinuations.Pending.class::cast)
                .filter(pending -> pending.bindings().values().stream().anyMatch(DamageSnapshot.class::isInstance)).toList();
    }
    private static DamageSnapshot snapshot(EffectContinuations.Pending pending) {
        return pending.bindings().values().stream().filter(DamageSnapshot.class::isInstance)
                .map(DamageSnapshot.class::cast).findFirst().orElseThrow();
    }
    private static RuleEngine.Signal signal(NativeCharacterTransferGameTest.Fixture f, String type) {
        return new RuleEngine.Signal(type, new EffectEvent(f.holder, f.holder,
                new BuffInstance.Origin(f.holder, "", "", ""), Set.of(), Map.of()));
    }
    private static long cues(NativeCharacterTransferGameTest.Fixture f, String cue) {
        return f.cues.stream().filter(cue::equals).count();
    }
    private static void firstHit(GameTestHelper h, NativeCharacterTransferGameTest.Fixture f, DamageSnapshot captured) {
        f.departure.prepare(); f.arrival.prepare(); f.healthy(); f.located(f.remote);
        h.assertTrue(f.state(f.remote).buffs().timeMicros() < captured.capturedAt(), "arrival clock caught up with capture and stopped exercising cross-clock damage");
        near(h, f.player.getHealth(), 14, "first native hit did not combine frozen on_use and newly acquired on_hit modifiers");
        h.assertValueEqual(f.damageCalls, 1, "first snapshot damage skipped or executed more than once");
        h.assertTrue(!hasBuff(f.state(f.remote), f.holder, "test:on_use") && !hasBuff(f.state(f.remote), f.holder, "test:on_hit"),
                "first hit did not leave both source bonuses removed");
        h.assertValueEqual(cues(f, "test:snapshot_first"), 1L, "first snapshot continuation did not complete exactly once");
        h.assertValueEqual(cues(f, "test:snapshot_hit"), 1L, "first snapshot hit reaction duplicated or lost its historical source");
        h.assertValueEqual(snapshot(continuations(f.state(f.remote)).getFirst()), captured, "first hit replaced or rebased the captured attack");
    }
    private static void run(GameTestHelper h, boolean restore) throws Exception {
        var f = new NativeCharacterTransferGameTest.Fixture(h, program(restore));
        try {
            f.player.setHealth(20);
            f.departure.start(signal(f, "test:snapshot"));
            var pending = continuations(f.state(f.local)).getFirst();
            var captured = snapshot(pending); var origin = captured.captureOrigin().orElseThrow();
            var identity = f.host.checkpoint().resources().domains().get(domain(f.local)).identity().orElseThrow();
            h.assertValueEqual(origin.domain(), domain(f.local), "snapshot did not pin its original physical capture domain");
            h.assertValueEqual(origin.timeline(), identity, "snapshot did not pin the source session timeline identity");
            h.assertTrue(captured.capturedAt() >= NativeCharacterTransferGameTest.START, "fixture did not capture at the high source clock");
            h.assertTrue(restore ? pending.scope() instanceof BuffRules.Scope : pending.scope() instanceof EffectSource,
                    "fixture did not exercise the intended continuation owner");
            h.assertTrue(!hasBuff(f.state(f.local), f.holder, "test:on_use"), "frozen modifier remained live after capture");
            h.assertTrue(f.move(f.remote, _ -> {}) == f.player, "native teleport replaced the captured player's body");
            NativeMotionGameTest.acknowledge(f.player);
            f.player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            h.assertTrue(!f.player.isChangingDimension() && f.player.connection.hasClientLoaded(), "embedded client did not acknowledge native arrival");
            f.located(f.remote); f.healthy();
            h.assertTrue(continuations(f.state(f.local)).isEmpty(), "departure kept its moved snapshot continuation");
            h.assertValueEqual(snapshot(continuations(f.state(f.remote)).getFirst()), captured, "native transfer rewrote historical capture data");
            h.assertTrue(f.state(f.remote).buffs().timeMicros() < captured.capturedAt(), "fixture does not have distinct source and destination clocks");
            f.arrival.start(signal(f, "test:snapshot_live"));
            h.assertTrue(hasBuff(f.state(f.remote), f.holder, "test:on_hit"), "destination did not acquire its live hit modifier");
            h.assertValueEqual(f.damageCalls, 0, "transfer executed the delayed snapshot early");
            h.runAfterDelay(5, () -> {
                try {
                    firstHit(h, f, captured);
                    if (restore) {
                        var continuation = continuations(f.state(f.remote)).getFirst();
                        f.restoreArchive();
                        h.assertValueEqual(f.host.checkpoint().resources().domains().get(domain(f.local)).identity().orElseThrow(), identity,
                                "joint archive replaced the source timeline identity");
                        h.assertValueEqual(continuations(f.state(f.remote)).getFirst(), continuation, "archive changed the pending snapshot or its remaining work");
                        firstHit(h, f, captured);
                    }
                } catch (RuntimeException | Error error) { f.close(); throw error; }
            });
            h.runAfterDelay(18, () -> {
                try {
                    f.departure.prepare(); f.arrival.prepare(); f.healthy(); f.located(f.remote);
                    h.assertTrue(f.state(f.remote).buffs().timeMicros() < captured.capturedAt(), "second hit no longer exercises the lower destination clock");
                    near(h, f.player.getHealth(), 9.5, "second hit did not retain on_use while dropping the removed on_hit contribution");
                    h.assertValueEqual(f.damageCalls, 2, "second native snapshot member skipped or repeated");
                    h.assertValueEqual(cues(f, "test:snapshot_first"), 1L, "first continuation replayed");
                    h.assertValueEqual(cues(f, "test:snapshot_second"), 1L, "second continuation did not finish exactly once");
                    h.assertValueEqual(cues(f, "test:snapshot_hit"), 2L, "snapshot damage facts or historical reactions repeated");
                    h.assertTrue(continuations(f.state(f.local)).isEmpty() && continuations(f.state(f.remote)).isEmpty(), "completed snapshot work stayed scheduled");
                    f.host.checkpoint();
                    if (restore) f.restoreArchive();
                } catch (RuntimeException | Error error) { f.close(); throw error; }
            });
            h.runAfterDelay(23, () -> {
                try (f) {
                    f.departure.prepare(); f.arrival.prepare(); f.healthy(); f.located(f.remote);
                    near(h, f.player.getHealth(), 9.5, "completed snapshot restore or later ticks replayed physical damage");
                    h.assertValueEqual(f.damageCalls, 2, "confirmed snapshot damage replayed after completion");
                    h.assertValueEqual(cues(f, "test:snapshot_second"), 1L, "completed snapshot payoff replayed");
                    f.host.checkpoint(); h.succeed();
                }
            });
        } catch (Exception | Error error) { f.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:snapshot_native_transfer", maxTicks = 50)
    public void nativeTransferResolvesHighClockSnapshotWithFrozenUseAndCurrentHitModifiers(GameTestHelper h) throws Exception { run(h, false); }
    @GameCase(environment = "chorus_gametest:snapshot_native_restore", maxTicks = 50)
    public void jointArchiveKeepsBuffOwnedCaptureTimelineWithoutReplayingNativeDamage(GameTestHelper h) throws Exception { run(h, true); }
}
