package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.*;
import static com.imdomestic.chorus.test.ResourceRuntimeGameTest.domain;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageTallies;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

/** Real damage executes in the new dimension while its captured accounting remains at the issuer. */
public class TallyTransferGameTest {
    static com.imdomestic.chorus.effect.data.CompiledEffects program() {
        var data = NativeCharacterTransferGameTest.data();
        data.add("profiles", JsonParser.parseString("""
            [{"id":"test:tally_damage","version":"native-character-transfer","input_unit":"damage","steps":[]}]
            """));
        var rules = data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules");
        rules.add(JsonParser.parseString("""
            {"id":"tally","on":"test:tally","do":[
              {"action":{"type":"chorus:begin_damage_tally","duration":{"type":"chorus:constant","value":5,"unit":"second"}},"as":"tally"},
              {"after":{"type":"chorus:constant","value":0.2,"unit":"second"},"do":[
                {"action":{"type":"chorus:damage","target":"self","amount":{"type":"chorus:constant","value":3,"unit":"damage"},"damage_type":"minecraft:generic","scaling_profile":"test:tally_damage"},"as":"hit"},
                {"type":"chorus:record_damage","tally":"tally","damage":"hit"},
                {"action":{"type":"chorus:read_damage_tally","tally":"tally"},"as":"read"},
                {"after":{"type":"chorus:constant","value":0.2,"unit":"second"},"do":[
                  {"action":{"type":"chorus:close_damage_tally","tally":"tally"},"as":"closed"},
                  {"type":"chorus:heal","amount":{"type":"chorus:result","binding":"closed","field":"health_loss"}},
                  {"type":"chorus:play_cue","cue":"test:tally_closed"}
                ]}
              ]}
            ]}
            """));
        return compile(data);
    }
    private static void run(GameTestHelper h, boolean restore) throws Exception {
        var f = new NativeCharacterTransferGameTest.Fixture(h, program());
        try {
            f.player.setHealth(10);
            f.departure.start(new RuleEngine.Signal("test:tally", new EffectEvent(f.holder, f.holder,
                    new BuffInstance.Origin(f.holder, "", "", ""), Set.of(), Map.of())));
            var handle = f.state(f.local).damageTallies().values().iterator().next().handle();
            h.assertValueEqual(handle.issuer(), Optional.of(domain(f.local)), "begin omitted its issuing dimension");
            h.assertTrue(f.move(f.remote, _ -> {}) == f.player, "native teleport did not carry the player");
            // The embedded client must acknowledge its new level before vanilla allows damage again.
            NativeMotionGameTest.acknowledge(f.player);
            f.player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
            h.assertTrue(!f.player.isChangingDimension() && f.player.connection.hasClientLoaded(), "embedded client did not acknowledge arrival");
            f.located(f.remote); f.healthy();
            h.assertTrue(f.state(f.local).damageTallies().containsKey(handle.id()), "transfer moved original tally ledger");
            h.assertTrue(f.state(f.remote).damageTallies().isEmpty(), "transfer copied tally into destination");
            h.runAfterDelay(5, () -> {
                try {
                    f.departure.prepare(); f.arrival.prepare(); f.healthy();
                    near(h, f.player.getHealth(), 7, "delayed damage did not affect actual player in destination");
                    var summary = DamageTallies.read(f.state(f.local), handle).numbers();
                    h.assertValueEqual(summary.attempts(), 1, "issuer did not record one actual receipt");
                    h.assertValueEqual(f.damageCalls, 1, "destination damage was executed more than once");
                    near(h, summary.healthLoss(), 3, "issuer tally differs from actual destination loss");
                    h.assertTrue(f.state(f.remote).damageTallies().isEmpty(), "record created a destination tally");
                    if (restore) f.restoreArchive();
                    h.assertValueEqual(f.damageCalls, 1, "archive restore replayed a confirmed damage command");
                } catch (RuntimeException | Error error) { f.close(); throw error; }
            });
            h.runAfterDelay(12, () -> {
                try (f) {
                    f.departure.prepare(); f.arrival.prepare(); f.healthy(); f.located(f.remote);
                    near(h, f.player.getHealth(), 10, "routed close did not restore exactly the confirmed loss");
                    h.assertValueEqual(f.heals, 1, "payoff repeated across native transfer or restore");
                    h.assertValueEqual(f.damageCalls, 1, "confirmed damage replayed after transfer or restore");
                    h.assertTrue(f.state(f.local).damageTallies().isEmpty() && f.state(f.remote).damageTallies().isEmpty(), "close left a live tally");
                    h.assertValueEqual(f.cues.stream().filter("test:tally_closed"::equals).count(), 1L, "close continuation did not execute exactly once");
                    f.host.checkpoint(); h.succeed();
                }
            });
        } catch (Exception | Error error) { f.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:tally_native_transfer", maxTicks = 30)
    public void nativeTransferRecordsDestinationDamageAndClosesOriginalTally(GameTestHelper h) throws Exception { run(h, false); }
    @GameCase(environment = "chorus_gametest:tally_native_restore", maxTicks = 30)
    public void jointArchiveRestoresIssuerLedgerAndMigratedPayoffWithoutReplayingDamage(GameTestHelper h) throws Exception { run(h, true); }
}
