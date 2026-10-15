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
import com.imdomestic.chorus.effect.combat.DamageGroups;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.resource.ParticipantPlacement;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;

/** Native player transport moves the continuation and Buff; the attack group stays at its issuer. */
public class DamageGroupTransferGameTest {
    private static CompiledEffects program(boolean buffOwned) {
        var data = NativeCharacterTransferGameTest.data();
        data.add("profiles", JsonParser.parseString("""
            [{"id":"test:group_damage","version":"native-character-transfer","input_unit":"damage",
              "steps":[{"type":"chorus:apply","id":"bonus","operation":"multiply","group":{"name":"bonus","reduction":"sum"}}]}]
            """));
        data.getAsJsonArray("buffs").add(JsonParser.parseString("""
            {"definition":{"id":"test:next_hit","version":"native-character-transfer","duration":"permanent"},
             "bundle":"test:next_hit","consume_on_damage":{"when":"hit","sharing":"group"}}
            """));
        data.getAsJsonArray("bundles").add(JsonParser.parseString("""
            {"id":"test:next_hit","scope":"buff",
             "rules":[{"id":"consumed","on":"chorus:buff_ended","if":{"type":"chorus:own_buff"},
               "do":[{"type":"chorus:play_cue","cue":"test:group_consumed"}]}],
             "modifiers":[{"id":"bonus","profile":"test:group_damage","stage":"bonus","group":"bonus","op":"multiply",
               "stacking_key":"test:next_hit","evaluate":"on_hit","value":{"type":"chorus:constant","value":1,"unit":"delta"},
               "reference":"Synthetic native group transfer fixture","confidence":"assumed"}]}
            """));
        var grouped = JsonParser.parseString("""
            [{"action":{"type":"chorus:begin_damage_group","lifetime":{"type":"chorus:constant","value":5,"unit":"second"}},"as":"group"},
             {"after":{"type":"chorus:constant","value":0.2,"unit":"second"},"do":[
               {"type":"chorus:damage","target":"self","amount":{"type":"chorus:constant","value":3,"unit":"damage"},
                "damage_type":"minecraft:generic","scaling_profile":"test:group_damage","group":"group"},
               {"after":{"type":"chorus:constant","value":0.6,"unit":"second"},"do":[
                 {"type":"chorus:damage","target":"self","amount":{"type":"chorus:constant","value":3,"unit":"damage"},
                  "damage_type":"minecraft:generic","scaling_profile":"test:group_damage","group":"group"},
                 {"after":{"type":"chorus:constant","value":0.2,"unit":"second"},"do":[
                   {"type":"chorus:end_damage_group","group":"group"},
                   {"type":"chorus:play_cue","cue":"test:group_closed"}
                 ]}
               ]}
             ]}]
            """).getAsJsonArray();
        var actorRules = data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules");
        var start = JsonParser.parseString("""
            {"id":"group","on":"test:group","do":[{"type":"chorus:grant_buff","buff":"test:next_hit"}]}
            """).getAsJsonObject();
        if (buffOwned) {
            data.getAsJsonArray("buffs").add(JsonParser.parseString("""
                {"definition":{"id":"test:group_carrier","version":"native-character-transfer","duration":"permanent"},"bundle":"test:group_carrier"}
                """));
            start.getAsJsonArray("do").add(JsonParser.parseString("""
                {"type":"chorus:grant_buff","buff":"test:group_carrier"}
                """));
            var carrier = JsonParser.parseString("""
                {"id":"test:group_carrier","scope":"buff","rules":[
                  {"id":"group","on":"chorus:buff_gained","if":{"type":"chorus:own_buff"},"do":[]}]}
                """).getAsJsonObject();
            carrier.getAsJsonArray("rules").get(0).getAsJsonObject().add("do", grouped);
            data.getAsJsonArray("bundles").add(carrier);
        } else {
            for (var step : grouped) start.getAsJsonArray("do").add(step);
        }
        actorRules.add(start);
        actorRules.add(JsonParser.parseString("""
            {"id":"group_hit","on":"chorus:hit","binding":"origin_bundle","do":[{"type":"chorus:play_cue","cue":"test:group_hit"}]}
            """));
        return compile(data);
    }
    private static List<BuffInstance> charges(EffectState state, String holder) {
        return state.buffs().instances().values().stream()
                .filter(buff -> buff.key().holder().equals(holder) && buff.definition().id().equals("test:next_hit")).toList();
    }
    private static EffectContinuations.Pending continuation(EffectState state, DamageGroups.Handle handle) {
        return state.timers().values().stream().map(timer -> timer.signal().payload())
                .filter(EffectContinuations.Pending.class::isInstance).map(EffectContinuations.Pending.class::cast)
                .filter(pending -> pending.bindings().containsValue(handle)).findFirst().orElseThrow();
    }
    private static long cues(NativeCharacterTransferGameTest.Fixture f, String cue) {
        return f.cues.stream().filter(cue::equals).count();
    }
    private static void firstHit(GameTestHelper h, NativeCharacterTransferGameTest.Fixture f,
            DamageGroups.Handle handle, BuffInstance moved) {
        f.departure.prepare(); f.arrival.prepare(); f.healthy(); f.located(f.remote);
        near(h, f.player.getHealth(), 14, "first grouped damage did not apply its doubled amount to the native destination player");
        h.assertValueEqual(f.damageCalls, 1, "first member damage executed more than once");
        h.assertTrue(charges(f.state(f.local), f.holder).isEmpty() && charges(f.state(f.remote), f.holder).isEmpty(),
                "first member did not consume the Buff from its migrated domain");
        var group = DamageGroups.require(f.state(f.local), handle);
        h.assertValueEqual(group.grants(), Map.of(moved.key(), moved), "issuer retained a different Buff identity or generation");
        h.assertTrue(f.state(f.remote).damageGroups().isEmpty(), "first member copied its issuer ledger to the destination");
        h.assertValueEqual(cues(f, "test:group_consumed"), 1L, "first member skipped or duplicated the actual consumption reaction");
        h.assertValueEqual(cues(f, "test:group_hit"), 1L, "first member skipped or duplicated its primary hit reaction");
    }
    private static void run(GameTestHelper h, boolean restore) throws Exception {
        var f = new NativeCharacterTransferGameTest.Fixture(h, program(restore));
        try {
            f.player.setHealth(20);
            f.departure.start(new RuleEngine.Signal("test:group", new EffectEvent(f.holder, f.holder,
                    new BuffInstance.Origin(f.holder, "", "", ""), Set.of(), Map.of())));
            var handle = f.state(f.local).damageGroups().values().iterator().next().handle();
            h.assertValueEqual(handle.issuer(), Optional.of(domain(f.local)), "begin did not qualify the original issuer");
            var pending = continuation(f.state(f.local), handle);
            h.assertTrue(restore ? pending.scope() instanceof BuffRules.Scope : pending.scope() instanceof EffectSource,
                    "fixture did not exercise the intended continuation ownership");
            h.assertTrue(f.move(f.remote, _ -> {}) == f.player, "native teleport did not carry the same player");
            NativeMotionGameTest.acknowledge(f.player);
            f.player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            h.assertTrue(!f.player.isChangingDimension() && f.player.connection.hasClientLoaded(), "embedded client did not acknowledge native arrival");
            f.located(f.remote); f.healthy();
            h.assertValueEqual(f.host.participants().owner(ParticipantPlacement.Facet.BUFFS, f.holder).domain(), domain(f.remote),
                    "native player transfer did not move its independent Buff authority");
            var moved = charges(f.state(f.remote), f.holder).getFirst();
            h.assertTrue(charges(f.state(f.local), f.holder).isEmpty(), "departure kept a live copy of the one-use Buff");
            h.assertValueEqual(continuation(f.state(f.remote), handle).bindings(), pending.bindings(), "transfer changed the captured group issuer or lifetime");
            h.assertTrue(f.state(f.local).damageGroups().containsKey(handle.id()) && f.state(f.remote).damageGroups().isEmpty(),
                    "native transfer moved or copied the original group ledger");
            h.assertValueEqual(f.state(f.local).timers().get(handle.timerId()).dueAt(), handle.dueAt(), "native transfer rebased the issuer expiry clock");
            h.assertValueEqual(f.damageCalls, 0, "native transfer executed the delayed damage early");
            h.runAfterDelay(5, () -> {
                try {
                    firstHit(h, f, handle, moved);
                    if (restore) {
                        var group = DamageGroups.require(f.state(f.local), handle);
                        var remaining = continuation(f.state(f.remote), handle);
                        f.restoreArchive();
                        h.assertValueEqual(DamageGroups.require(f.state(f.local), handle), group, "archive changed the original issuer's retained eligibility");
                        h.assertValueEqual(continuation(f.state(f.remote), handle), remaining, "archive changed the moved Buff-owned continuation");
                        firstHit(h, f, handle, moved);
                    }
                } catch (RuntimeException | Error error) { f.close(); throw error; }
            });
            h.runAfterDelay(17, () -> {
                try {
                    f.departure.prepare(); f.arrival.prepare(); f.healthy(); f.located(f.remote);
                    near(h, f.player.getHealth(), 8, "second native member lost its original issuer's retained damage bonus");
                    h.assertValueEqual(f.damageCalls, 2, "second member skipped or repeated an actual damage command");
                    h.assertTrue(charges(f.state(f.remote), f.holder).isEmpty(), "second member recreated its consumed Buff");
                    h.assertValueEqual(DamageGroups.require(f.state(f.local), handle).grants(), Map.of(moved.key(), moved),
                            "second member changed the retained grant or consumed another generation");
                    h.assertValueEqual(cues(f, "test:group_consumed"), 1L, "second member consumed or emitted the one-use Buff twice");
                    h.assertValueEqual(cues(f, "test:group_hit"), 2L, "second member did not emit exactly one additional hit");
                } catch (RuntimeException | Error error) { f.close(); throw error; }
            });
            h.runAfterDelay(23, () -> {
                try (f) {
                    f.departure.prepare(); f.arrival.prepare(); f.healthy(); f.located(f.remote);
                    near(h, f.player.getHealth(), 8, "group completion or archive replay changed native health");
                    h.assertValueEqual(f.damageCalls, 2, "confirmed damage replayed after group completion");
                    h.assertValueEqual(cues(f, "test:group_consumed"), 1L, "group completion repeated Buff consumption");
                    h.assertValueEqual(cues(f, "test:group_closed"), 1L, "migrated group close skipped or repeated");
                    for (var level : List.of(f.local, f.remote)) {
                        h.assertTrue(f.state(level).damageGroups().isEmpty(), "close left a ghost group ledger");
                        h.assertTrue(!f.state(level).timers().containsKey(handle.timerId()), "close left a ghost issuer expiry");
                    }
                    f.host.checkpoint();
                    if (restore) {
                        f.restoreArchive();
                        h.assertValueEqual(f.damageCalls, 2, "restoring the completed group replayed native damage");
                        h.assertValueEqual(cues(f, "test:group_closed"), 1L, "restoring the completed group replayed close");
                        near(h, f.player.getHealth(), 8, "restoring the completed group changed physical health");
                    }
                    h.succeed();
                }
            });
        } catch (Exception | Error error) { f.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:damage_group_native_transfer", maxTicks = 50)
    public void nativeTransferKeepsSourceOwnedGroupAtIssuerThroughConsumptionAndClose(GameTestHelper h) throws Exception { run(h, false); }
    @GameCase(environment = "chorus_gametest:damage_group_native_restore", maxTicks = 50)
    public void jointArchiveRestoresBuffOwnedGroupWithoutReplayingFirstNativeConsumption(GameTestHelper h) throws Exception { run(h, true); }
}
