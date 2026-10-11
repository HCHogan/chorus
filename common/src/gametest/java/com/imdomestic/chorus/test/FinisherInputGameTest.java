package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.network.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;

public class FinisherInputGameTest {
    public static CompiledEffects program() {
        var d = ThreadedSpikeGameTest.json("finisher"); GamblersDodgeGameTest.merge(d, ThreadedSpikeGameTest.json("finisher_input"));
        return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, d).getOrThrow();
    }
    public static EffectSource input(LivingEntity actor) { String id = actor.getUUID().toString(); return new EffectSource("input/" + id, "test:finisher_input", id, new BuffInstance.Origin(id, "input/" + id, "", ""), Set.of()); }
    public static EffectSource observer(LivingEntity actor) { return FinisherGameTest.source(actor); }
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "finisher", d -> GamblersDodgeGameTest.merge(d, ThreadedSpikeGameTest.json("finisher_input")), true);
        try { t.owner.setYRot(0); t.owner.setXRot(0); t.runtime.bind(input(t.owner)); t.runtime.bind(observer(t.owner)); return t; } catch (Exception | Error e) { t.close(); throw e; }
    }
    static LivingEntity target(ProjectileGameTest.Harness t, double z, float health) { var e = t.cow(2.5, 40, z); e.addTag("test:finishable"); e.setHealth(health); return e; }
    static FinisherInputPayload packet(ProjectileGameTest.Harness t, long n) { return new FinisherInputPayload(n, t.owner.level().dimension().identifier().toString()); }
    @GameCase(environment="chorus_gametest:finisher_input_selection", maxTicks=40)
    public void unprivilegedCommandSkipsIneligibleNearestAndSelectsOneEligibleForwardTarget(GameTestHelper h) throws Exception {
        var t = open(h); try { var high = target(t, 4.5, 80); var eligible = target(t, 5.5, 20); var next = target(t, 6, 20); var behind = target(t, 2.5, 20);
            int result = h.getLevel().getServer().getCommands().getDispatcher().execute("chorus finisher", t.owner.createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS).withSuppressedOutput());
            h.assertValueEqual(result, 1, "self-owned command needs no operator permission"); h.assertValueEqual(FinisherGameTest.count(t, "started"), 1L, "one eligible candidate accepted");
            t.finish(10, () -> { h.assertValueEqual(t.hits.size(), 1, "one selected victim"); h.assertValueEqual(t.hits.getFirst().target(), eligible.getUUID().toString(), "nearest authorized target selected");
                h.assertTrue(high.isAlive() && next.isAlive() && behind.isAlive(), "other candidates untouched"); h.assertValueEqual(FinisherGameTest.count(t, "completed"), 1L, "actual completion"); });
        } catch (Throwable e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:finisher_input_packets", maxTicks=40)
    public void sequenceDimensionCodecAndBusyInputsCannotReplayOrQueueAnotherFinisher(GameTestHelper h) throws Exception {
        var t = open(h); var network = new FinisherInputNetworkServer();
        try { target(t, 5.5, 20); var spare = target(t, 6, 20);
            h.assertValueEqual(network.request(t.owner, new FinisherInputPayload(1, "test:elsewhere")), FinisherInputNetworkServer.Reply.REJECTED, "wrong dimension");
            h.assertValueEqual(network.request(t.owner, packet(t, 1)), FinisherInputNetworkServer.Reply.DUPLICATE, "rejected sequence stays consumed");
            var p = packet(t, 2); var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
            try { FinisherInputPayload.CODEC.encode(buffer, p); h.assertValueEqual(FinisherInputPayload.CODEC.decode(buffer), p, "registered payload codec"); } finally { buffer.release(); }
            h.assertValueEqual(network.request(t.owner, p), FinisherInputNetworkServer.Reply.DISPATCHED, "input delivered"); int operations = t.operations.size();
            h.assertValueEqual(network.request(t.owner, p), FinisherInputNetworkServer.Reply.DUPLICATE, "duplicate input");
            h.assertValueEqual(network.request(t.owner, packet(t, 3)), FinisherInputNetworkServer.Reply.BUSY, "busy input is not deferred"); h.assertValueEqual(t.operations.size(), operations, "no extra world scans while busy");
            t.finish(12, () -> { h.assertValueEqual(t.hits.size(), 1, "no buffered second execution"); h.assertTrue(spare.isAlive(), "busy input did not finish spare");
                h.assertValueEqual(network.request(t.owner, packet(t, 3)), FinisherInputNetworkServer.Reply.DUPLICATE, "busy sequence stays consumed after completion"); network.disconnected(t.owner.connection); });
        } catch (Throwable e) { network.disconnected(t.owner.connection); t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:finisher_input_ability", maxTicks=40)
    public void independentFinisherInputDoesNotBecomeAnOrdinaryAbilityOrRequireItsEnergyAccount(GameTestHelper h) throws Exception {
        var t = open(h); try { target(t, 5.5, 20); var s = input(t.owner); t.runtime.bind(new EffectSource("gate", "test:block_abilities", s.holder(), s.origin(), Set.of()));
            h.assertTrue(t.runtime.state().engine().domain().abilities().isEmpty(), "no fake ability slot installed"); h.assertTrue(t.runtime.requestFinisher(t.owner), "independent input dispatched");
            t.finish(10, () -> { h.assertValueEqual(FinisherGameTest.count(t, "completed"), 1L, "ordinary ability restriction does not govern finishers");
                h.assertTrue(t.runtime.state().engine().domain().resources().isEmpty(), "no fabricated or paid ability account"); });
        } catch (Throwable e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:finisher_input_ineligible")
    public void deadAndSpectatorSendersCannotRequestServerEffects(GameTestHelper h) throws Exception {
        var network = new FinisherInputNetworkServer(); try (var t = open(h)) { target(t, 5.5, 20); int operations = t.operations.size();
            t.owner.setHealth(0); h.assertValueEqual(network.request(t.owner, packet(t, 1)), FinisherInputNetworkServer.Reply.REJECTED, "dead sender");
            t.owner.setHealth(10); t.owner.setGameMode(GameType.SPECTATOR); h.assertValueEqual(network.request(t.owner, packet(t, 2)), FinisherInputNetworkServer.Reply.REJECTED, "spectator sender");
            h.assertValueEqual(t.operations.size(), operations, "invalid sender never dispatches query or action"); network.disconnected(t.owner.connection);
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:finisher_input_unknown")
    public void unknownStartReactionConsumesThePacketAndCannotReplayItsCommittedReservation(GameTestHelper h) throws Exception {
        var network = new FinisherInputNetworkServer(); try (var t = open(h)) { target(t, 5.5, 20); t.failAfterCue = true; var p = packet(t, 1);
            h.assertValueEqual(network.request(t.owner, p), FinisherInputNetworkServer.Reply.FAILED, "unknown start reaction"); h.assertTrue(FinisherGameTest.busy(t, t.owner), "accepted reservation survives failure"); int operations = t.operations.size();
            h.assertValueEqual(network.request(t.owner, p), FinisherInputNetworkServer.Reply.DUPLICATE, "same input cannot replay");
            h.assertValueEqual(network.request(t.owner, packet(t, 2)), FinisherInputNetworkServer.Reply.FAILED, "new packet cannot resume stopped runtime"); h.assertValueEqual(t.operations.size(), operations, "no repeated world effects"); network.disconnected(t.owner.connection);
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:finisher_input_binding", maxTicks=40)
    public void missingContentCannotInventAnExecutionAndUnbindingDoesNotUndoAnAcceptedOne(GameTestHelper h) throws Exception {
        var t = open(h); try { target(t, 5.5, 20); t.runtime.unbind(input(t.owner).instance()); t.runtime.requestFinisher(t.owner);
            h.assertValueEqual(FinisherGameTest.count(t, "started"), 0L, "input alone has no finisher definition"); t.runtime.bind(input(t.owner)); t.runtime.requestFinisher(t.owner); t.runtime.unbind(input(t.owner).instance());
            t.finish(10, () -> { h.assertValueEqual(FinisherGameTest.count(t, "completed"), 1L, "accepted plan survives source unbinding"); h.assertValueEqual(t.hits.getFirst().source(), input(t.owner).origin(), "accepted origin retained"); });
        } catch (Throwable e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:finisher_input_aim", maxTicks=40)
    public void targetSelectionUsesCurrentServerFacingRatherThanAClientTargetIdentity(GameTestHelper h) throws Exception {
        var t = open(h); try { var side = FinisherGameTest.target(t, 4.5); t.runtime.requestFinisher(t.owner); h.assertValueEqual(FinisherGameTest.count(t, "started"), 0L, "side target outside cone");
            t.owner.setYRot(-90); t.runtime.requestFinisher(t.owner);
            t.finish(10, () -> { h.assertValueEqual(t.hits.size(), 1, "new facing acquired target"); h.assertValueEqual(t.hits.getFirst().target(), side.getUUID().toString(), "server selected actual target"); });
        } catch (Throwable e) { t.close(); throw e; }
    }
}
