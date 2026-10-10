package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.network.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;

public class AbilityInputGameTest {
    @GameCase public void heldInputChecksTheResolvedVariantBeforeApplyingActionRestrictions(GameTestHelper h) {
        try (var t = new Harness(h)) {
            t.bind("base_gate", "base_gate");
            h.assertValueEqual(t.input(AbilityInput.Edge.PRESS, 1).outcome(), AbilityInput.Outcome.RESTRICTED, "base-only gate absent");
            t.runtime.unbind("convert"); t.bind("replacement", "unconditional_conversion");
            h.assertValueEqual(t.input(AbilityInput.Edge.PRESS, 2).outcome(), AbilityInput.Outcome.PRESSED, "base-only gate blocked allowed replacement");
            h.assertValueEqual(t.input(AbilityInput.Edge.RELEASE, 2).cast().orElseThrow().outcome(), AbilityUse.Outcome.ACCEPTED, "allowed variant release rejected"); near(h, t.energy(), 9, "allowed variant payment");
        } h.succeed();
    }
    public static final String SLOT = "chorus_d2:grenade";
    public static CompiledEffects program() { return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("ability_input")).getOrThrow(); }
    static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer player; final MinecraftEffectRuntime runtime; final List<HealingCommand> heals = new ArrayList<>(); boolean fail;
        Harness(GameTestHelper h) {
            this.h = h; player = NativeMeleeGameTest.player(h); player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); player.setHealth(10);
            var world = new MinecraftWorldActions(h.getLevel(), id -> id.equals(id()) ? player : null, _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program(), EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), r -> {
                if (r.command() instanceof HealingCommand c) heals.add(c); var result = world.apply(r); if (fail) throw new IllegalStateException("Unknown released ability result"); return result;
            }, MinecraftEffectRuntime::nativeSource);
            choose("tap"); bind("convert", "conversion"); bind("control", "control");
        }
        String id() { return player.getUUID().toString(); }
        EffectState state() { return runtime.state().engine().domain(); }
        void choose(String name) { runtime.abilities(new AbilityChange(id(), state().abilities().getOrDefault(id(), AbilityLoadout.EMPTY), new AbilityLoadout(Map.of(SLOT, "test:" + name)))); }
        void bind(String instance, String bundle) { runtime.bind(new EffectSource(instance, "test:" + bundle, id(), new BuffInstance.Origin(id(), instance, "", ""), Set.of())); }
        AbilityInput.Receipt input(AbilityInput.Edge edge, long gesture) { return runtime.abilityInput(player, SLOT, edge, gesture); }
        double energy() { return state().resources().get(new ResourceState.Key(id(), "test:energy")).value(); }
        AbilityInputPayload packet(long sequence, long gesture, AbilityInput.Edge edge) { return new AbilityInputPayload(sequence, gesture, player.level().dimension().identifier().toString(), SLOT, edge); }
        void later(int ticks, Runnable check) { h.runAfterDelay(ticks, () -> { try { check.run(); } catch (RuntimeException | Error e) { close(); throw e; } }); }
        @Override public void close() { runtime.close(); player.discard(); }
    }
    @GameCase public void shortReleaseCastsOnceAndDirectUseCancelsAnOutstandingGesture(GameTestHelper h) {
        try (var t = new Harness(h)) {
            h.assertValueEqual(t.input(AbilityInput.Edge.RELEASE, 1).outcome(), AbilityInput.Outcome.NO_PRESS, "unpaired release");
            h.assertValueEqual(t.input(AbilityInput.Edge.PRESS, 1).outcome(), AbilityInput.Outcome.PRESSED, "press"); near(h, t.energy(), 10, "press paid early");
            var r = t.input(AbilityInput.Edge.RELEASE, 1); h.assertValueEqual(r.heldMicros(), 0L, "same tick hold"); h.assertValueEqual(r.cast().orElseThrow().resolved(), "test:tap", "tap converted");
            near(h, t.player.getHealth(), 11, "tap actual healing"); h.assertValueEqual(t.input(AbilityInput.Edge.RELEASE, 1).outcome(), AbilityInput.Outcome.NO_PRESS, "release replayed");
            t.input(AbilityInput.Edge.PRESS, 2); t.runtime.useAbility(t.player, SLOT);
            h.assertValueEqual(t.input(AbilityInput.Edge.RELEASE, 2).outcome(), AbilityInput.Outcome.NO_PRESS, "direct use left hold armed"); near(h, t.energy(), 8, "duplicate input paid");
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:ability_input_hold", maxTicks=25)
    public void serverTicksMeasureHoldAndRepeatedOrMismatchedEdgesCannotRestartOrReleaseIt(GameTestHelper h) {
        var t = new Harness(h); t.input(AbilityInput.Edge.PRESS, 1); long start = t.runtime.nowMicros();
        t.later(4, () -> { h.assertValueEqual(t.input(AbilityInput.Edge.PRESS, 2).outcome(), AbilityInput.Outcome.ALREADY_HELD, "repeated press restarted hold");
            h.assertValueEqual(t.input(AbilityInput.Edge.RELEASE, 2).outcome(), AbilityInput.Outcome.STALE, "different gesture released hold"); t.choose("tap"); });
        t.later(10, () -> { try (t) {
            var r = t.input(AbilityInput.Edge.RELEASE, 1); long elapsed = t.runtime.nowMicros() - start;
            h.assertValueEqual(r.heldMicros(), elapsed, "hold did not use original server timestamp"); h.assertValueEqual(r.cast().orElseThrow().resolved(), "test:hold", "long hold did not convert");
            near(h, t.player.getHealth(), 10 + elapsed / 100_000.0, "held measurement did not reach real effect"); near(h, t.energy(), 9, "conversion payment"); h.succeed();
        }});
    }
    @GameCase public void selectionChangesCancellationAndDisconnectDiscardPendingInputWithoutCost(GameTestHelper h) {
        try (var t = new Harness(h)) {
            t.input(AbilityInput.Edge.PRESS, 1); t.choose("other"); t.choose("tap");
            h.assertValueEqual(t.input(AbilityInput.Edge.RELEASE, 1).outcome(), AbilityInput.Outcome.NO_PRESS, "switch away and back retained hold");
            t.input(AbilityInput.Edge.PRESS, 2); h.assertValueEqual(t.input(AbilityInput.Edge.CANCEL, 2).outcome(), AbilityInput.Outcome.CANCELLED, "cancel");
            h.assertValueEqual(t.input(AbilityInput.Edge.RELEASE, 2).outcome(), AbilityInput.Outcome.NO_PRESS, "cancelled hold released");
            t.input(AbilityInput.Edge.PRESS, 3); new AbilityInputNetworkServer().disconnected(t.player.connection);
            h.assertValueEqual(t.input(AbilityInput.Edge.RELEASE, 3).outcome(), AbilityInput.Outcome.NO_PRESS, "disconnected hold survived"); near(h, t.energy(), 10, "cancellation paid");
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:ability_input_gate", maxTicks=20)
    public void temporaryActionRestrictionCancelsAChargeEvenAfterTheRestrictionExpires(GameTestHelper h) {
        var t = new Harness(h); t.input(AbilityInput.Edge.PRESS, 1);
        t.runtime.start(new RuleEngine.Signal("test:block", new EffectEvent(t.id(), t.id(), new BuffInstance.Origin(t.id(), "control", "", ""), Set.of(), Map.of())));
        h.assertTrue(t.runtime.heldAbilityInput(t.player, SLOT).isEmpty(), "restricted hold remained armed");
        h.assertValueEqual(t.input(AbilityInput.Edge.PRESS, 2).outcome(), AbilityInput.Outcome.RESTRICTED, "restricted press accepted");
        t.later(6, () -> { try (t) {
            h.assertValueEqual(t.input(AbilityInput.Edge.RELEASE, 1).outcome(), AbilityInput.Outcome.NO_PRESS, "expired restriction revived old hold");
            near(h, t.energy(), 10, "interrupted charge paid"); h.assertTrue(t.heals.isEmpty(), "interrupted charge fired"); h.succeed();
        }});
    }
    @GameCase public void deathSpectatorAndRuntimeReplacementCannotTransferHeldInputs(GameTestHelper h) {
        try (var t = new Harness(h)) {
            t.input(AbilityInput.Edge.PRESS, 1); t.player.setHealth(0); t.runtime.prepare(); t.player.setHealth(10);
            h.assertValueEqual(t.input(AbilityInput.Edge.RELEASE, 1).outcome(), AbilityInput.Outcome.NO_PRESS, "death retained hold");
            t.input(AbilityInput.Edge.PRESS, 2); t.player.setGameMode(GameType.SPECTATOR); t.runtime.prepare(); t.player.setGameMode(GameType.SURVIVAL);
            h.assertValueEqual(t.input(AbilityInput.Edge.RELEASE, 2).outcome(), AbilityInput.Outcome.NO_PRESS, "spectator retained hold");
            t.input(AbilityInput.Edge.PRESS, 3); t.runtime.close();
            var world = new MinecraftWorldActions(h.getLevel(), id -> id.equals(t.id()) ? t.player : null, _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            try (var replacement = MinecraftEffectRuntime.install(h.getLevel(), program(), t.state(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), world, MinecraftEffectRuntime::nativeSource)) {
                h.assertValueEqual(replacement.abilityInput(t.player, SLOT, AbilityInput.Edge.RELEASE, 3).outcome(), AbilityInput.Outcome.NO_PRESS, "runtime replacement inherited hold");
            }
        } h.succeed();
    }
    @GameCase public void packetSequenceDimensionAndGestureChecksRunBeforeAnyAbilityPayment(GameTestHelper h) {
        try (var t = new Harness(h)) {
            var network = new AbilityInputNetworkServer();
            var wrong = new AbilityInputPayload(1, 1, "test:elsewhere", SLOT, AbilityInput.Edge.PRESS);
            h.assertValueEqual(network.request(t.player, wrong).reply(), AbilityInputNetworkServer.Reply.REJECTED, "wrong dimension");
            h.assertValueEqual(network.request(t.player, t.packet(1, 1, AbilityInput.Edge.PRESS)).reply(), AbilityInputNetworkServer.Reply.DUPLICATE, "rejected sequence replayed");
            network.request(t.player, t.packet(2, 2, AbilityInput.Edge.PRESS));
            h.assertValueEqual(network.request(t.player, t.packet(3, 1, AbilityInput.Edge.RELEASE)).input().orElseThrow().outcome(), AbilityInput.Outcome.STALE, "old release matched new press");
            var encoded = t.packet(4, 2, AbilityInput.Edge.RELEASE); var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
            try { AbilityInputPayload.CODEC.encode(buffer, encoded); h.assertValueEqual(AbilityInputPayload.CODEC.decode(buffer), encoded, "input codec"); } finally { buffer.release(); }
            h.assertValueEqual(network.request(t.player, encoded).input().orElseThrow().cast().orElseThrow().outcome(), AbilityUse.Outcome.ACCEPTED, "release failed");
            h.assertValueEqual(network.request(t.player, encoded).reply(), AbilityInputNetworkServer.Reply.DUPLICATE, "duplicate release"); near(h, t.energy(), 9, "duplicate payment");
            network.disconnected(t.player.connection);
        } h.succeed();
    }
    @GameCase public void unknownReleasedWorldResultRetainsCostAndConsumesGestureAndPacketSequence(GameTestHelper h) {
        try (var t = new Harness(h)) {
            var network = new AbilityInputNetworkServer(); network.request(t.player, t.packet(1, 1, AbilityInput.Edge.PRESS)); t.fail = true;
            var release = t.packet(2, 1, AbilityInput.Edge.RELEASE);
            h.assertValueEqual(network.request(t.player, release).reply(), AbilityInputNetworkServer.Reply.FAILED, "unknown world result");
            near(h, t.player.getHealth(), 11, "actual released healing lost"); near(h, t.energy(), 9, "released cost refunded");
            h.assertValueEqual(network.request(t.player, release).reply(), AbilityInputNetworkServer.Reply.DUPLICATE, "unknown release replayed");
            h.assertTrue(t.runtime.heldAbilityInput(t.player, SLOT).isEmpty() && t.heals.size() == 1, "failed input retained gesture or repeated effect"); network.disconnected(t.player.connection);
        } h.succeed();
    }
}
