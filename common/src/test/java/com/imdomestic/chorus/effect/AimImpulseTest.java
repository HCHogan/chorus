package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.AimImpulse;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Unit;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AimImpulseTest {
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin("player", "provider", "weapon", "ability");
    static final class Harness {
        final CompiledEffects program;
        EffectSession session;
        AimImpulse.Rotation rotation = new AimImpulse.Rotation(179, -85);
        final List<AimImpulse.Receipt> receipts = new ArrayList<>();
        final List<HealingCommand> heals = new ArrayList<>();
        AimImpulse.Outcome reject;
        boolean unknown, mismatch;
        Harness() throws Exception {
            program = load("aim_impulse");
            session = new EffectSession(program, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1, EffectState.empty(), this::world);
            send(new AbilityChange("player", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:aim", "test:aim_kick"))).signal());
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            return switch (request.command()) {
                case AimImpulse.Command command -> {
                    assertEquals(0, energy());
                    var actual = mismatch ? new AimImpulse.Command("wrong", command.yaw(), command.pitch(), command.origin(), command.tags()) : command;
                    AimImpulse.Receipt receipt;
                    if (reject != null) receipt = AimImpulse.Receipt.rejected(actual, reject);
                    else {
                        var change = new AimImpulse.Change(rotation, rotation.add(command)); rotation = change.after();
                        receipt = new AimImpulse.Receipt(actual, change.changed() ? AimImpulse.Outcome.APPLIED : AimImpulse.Outcome.UNCHANGED, Optional.of(change));
                    }
                    receipts.add(receipt);
                    if (unknown) throw new IllegalStateException("Unknown aim write");
                    yield receipt;
                }
                case HealingCommand command -> {
                    heals.add(command); yield new HealingReceipt(request.id().toString(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
                }
                default -> throw new AssertionError(request.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        double energy() { return state().resources().get(new ResourceState.Key("player", "test:aim_energy")).value(); }
        void send(RuleEngine.Signal signal) { session.start(state().buffs().timeMicros(), signal); }
        void cast() { send(new AbilityUse.Request("player", "test:aim", "kick", new EffectEvent("player", "target", ORIGIN, Set.of(), Map.of())).signal()); }
        void until(long time) { session.observe(time, List.of()); }
        void resist() { send(SourceChange.bind(source("test:aim_resist"))); }
    }
    @Test void delayedPaidActionReadsCurrentAimAndReportsClampedActualDelta() throws Exception {
        var h = new Harness(); h.cast(); assertEquals(0, h.energy()); assertTrue(h.receipts.isEmpty());
        h.rotation = new AimImpulse.Rotation(230, -88); h.until(99_999); assertTrue(h.receipts.isEmpty()); h.until(100_000);
        assertEquals(new AimImpulse.Rotation(233, -90), h.rotation);
        var receipt = h.receipts.getFirst(); assertEquals(-2, receipt.requireChange().pitch()); assertEquals(3, receipt.requireChange().yaw());
        assertEquals("test:aim_kick", receipt.command().origin().ability()); assertEquals(Set.of("test:kick"), receipt.command().tags());
        assertEquals(List.of(2., 1.), h.heals.stream().map(HealingCommand::amount).toList());
        assertEquals(Unit.DEGREE, AimActions.RESULT.fields().get("delta_pitch").unit());
    }
    @Test void profileModifiesTheRequestedAngleBeforeNativeLimitsAndIsSampledAtExecution() throws Exception {
        var h = new Harness(); h.rotation = new AimImpulse.Rotation(0, 0); h.cast(); h.resist(); h.until(100_000);
        assertEquals(-6, h.receipts.getFirst().command().pitch()); assertEquals(new AimImpulse.Rotation(3, -6), h.rotation);
        assertEquals(List.of(6., 1.), h.heals.stream().map(HealingCommand::amount).toList());
    }
    @Test void rejectionsHaveNoMeasurementsOrCompletionAndKeepPaidCost() throws Exception {
        for (var outcome : List.of(AimImpulse.Outcome.DEAD, AimImpulse.Outcome.MISSING_TARGET, AimImpulse.Outcome.UNLOADED)) {
            var h = new Harness(); h.reject = outcome; h.cast(); h.until(100_000);
            assertEquals(0, h.energy()); assertTrue(h.heals.isEmpty());
            assertFalse(AimActions.RESULT.flags().get("observed").test(h.receipts.getFirst()));
            assertThrows(IllegalArgumentException.class, () -> AimActions.RESULT.fields().get("delta_yaw").read().applyAsDouble(h.receipts.getFirst()));
        }
    }
    @Test void unknownAndMismatchedWorldWritesCannotReplayOrRunReactions() throws Exception {
        for (boolean mismatch : List.of(false, true)) {
            var h = new Harness(); h.mismatch = mismatch; h.unknown = !mismatch; h.cast();
            assertThrows(IllegalStateException.class, () -> h.until(100_000)); assertEquals(0, h.energy());
            assertEquals(new AimImpulse.Rotation(182, -90), h.rotation); assertTrue(h.heals.isEmpty());
            assertThrows(IllegalStateException.class, () -> h.until(200_000)); assertEquals(1, h.receipts.size());
        }
    }
    @Test void detachedCheckpointRestoresPendingActionAndRetainsItsCaster() throws Exception {
        var h = new Harness(); h.cast(); h.send(new AbilityChange("player", h.state().abilities().get("player"), AbilityLoadout.EMPTY).signal());
        var saved = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(h.session.checkpoint()));
        h.session = EffectSession.restore(h.program, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 256, saved, h::world);
        h.until(100_000); assertEquals("player", h.receipts.getFirst().command().origin().owner());
        assertEquals(List.of(5.), h.heals.stream().map(HealingCommand::amount).toList());
        var receipt = h.receipts.getFirst();
        assertEquals(receipt, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(receipt), AimImpulse.Receipt.class));
    }
    @Test void angleUnitsAndHostRepresentabilityAreValidatedBeforeWorldWrites() throws Exception {
        var p = load("aim_impulse"); assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
        for (String variant : List.of("unit", "overflow")) {
            var data = json("aim_impulse");
            var yaw = data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use").get(0).getAsJsonObject()
                    .getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("yaw");
            if (variant.equals("unit")) yaw.addProperty("unit", "meter"); else yaw.addProperty("value", Double.MAX_VALUE);
            assertThrows(RuntimeException.class, () -> compile(data));
        }
        assertThrows(IllegalArgumentException.class, () -> new AimImpulse.Command("player", Double.NaN, 0, ORIGIN, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new AimImpulse.Rotation(0, 91));
        var zero = new AimImpulse.Command("player", 0, -10, ORIGIN, Set.of());
        var before = new AimImpulse.Rotation(0, -90); var change = new AimImpulse.Change(before, before.add(zero));
        assertFalse(change.changed());
        assertThrows(IllegalArgumentException.class, () -> new AimImpulse.Receipt(zero, AimImpulse.Outcome.APPLIED, Optional.of(change)));
        var huge = new AimImpulse.Command("player", Float.MAX_VALUE, 0, ORIGIN, Set.of());
        assertThrows(IllegalArgumentException.class, () -> new AimImpulse.Rotation(Float.MAX_VALUE, 0).add(huge));
    }
}
