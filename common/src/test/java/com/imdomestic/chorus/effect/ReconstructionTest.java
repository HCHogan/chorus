package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReconstructionTest {
    static Loadout pair(String a, String b, String drawn) {
        return new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", a)),
                "test:secondary", new Loadout.Gear("b", "test:rifle", Map.of("perk", b))), Optional.of("test:" + drawn));
    }
    static CompiledEffects program(int capacity, int magazine, OptionalInt reserves) throws Exception {
        var data = json("reconstruction_weapon"); var ammo = data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition");
        ammo.addProperty("capacity", capacity); ammo.addProperty("magazine", magazine);
        if (reserves.isEmpty()) ammo.addProperty("reserves", "unlimited");
        else { ammo.getAsJsonObject("reserves").addProperty("rounds", reserves.getAsInt()); ammo.getAsJsonObject("reserves").addProperty("capacity", Math.max(1000, reserves.getAsInt())); }
        return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(), load("reconstruction").program()));
    }
    static class Harness {
        final CompiledEffects program; EffectSession session; int sequence; boolean failRefill;
        final List<Action.CueCommand> cues = new ArrayList<>(); final List<ProjectileFlight.Launch> launches = new ArrayList<>();
        Harness() throws Exception { this(5, 5, OptionalInt.of(30)); }
        Harness(int capacity, int magazine, OptionalInt reserves) throws Exception {
            program = program(capacity, magazine, reserves);
            session = new EffectSession(program, EffectCheckpointTest.clock(), 1, EffectState.empty(), this::world);
            equip(pair("normal", "enhanced", "primary"));
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            return switch (request.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(WeaponFireTest.POINT));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 0, 1, 0)));
                case ProjectileFlight.Launch launch -> {
                    launches.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("projectile/" + launches.size()));
                }
                case WeaponReload.Verify q -> new WeaponReload.Verified(q, true);
                case Action.CueCommand cue -> { cues.add(cue); if (failRefill && cue.cue().equals("test:refilled") && cue.source().weapon().equals("a")) throw new IllegalStateException("unknown post-refill reaction"); yield RuleEngine.Empty.INSTANCE; }
                default -> throw new AssertionError(request.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        AmmoState ammo(String weapon) { return state().ammunition().get(weapon); }
        int magazine(String weapon) { return ammo(weapon).magazine(); }
        void until(long time) { session.observe(time, List.of()); }
        void equip(Loadout next) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), next).signal()); }
        WeaponFire.Outcome fire() {
            var request = new WeaponFire.Request("player", "shot/" + ++sequence); var receipt = (WeaponFire.Receipt) program.fire(state(), request).result();
            session.start(now(), request.signal()); return receipt.outcome();
        }
        void reload() { session.start(now(), new WeaponReload.Request("player", "reload/" + ++sequence).signal()); }
        long due(String weapon) { return state().timers().values().stream().filter(t -> t.signal().payload() instanceof EffectTimers.Scheduled s && s.owner().source().orElseThrow().origin().weapon().equals(weapon)).findFirst().orElseThrow().dueAt(); }
        void reserve(String weapon, int amount) { session.start(now(), new RuleEngine.Signal("test:reserve", new EffectEvent("player", "", new BuffInstance.Origin("player", "reserve", weapon, ""), Set.of(), Map.of("amount", new Measure(amount, Unit.ROUND))))); }
        long reloads() { return cues.stream().filter(c -> c.cue().equals("test:reloaded")).count(); }
        void restore() {
            var saved = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint()));
            session = EffectSession.restore(new CompiledEffects(saved.program()), EffectCheckpointTest.clock(), 256, saved, this::world);
        }
    }
    @Test void firstAndSuccessivePulsesUseNormalAndEnhancedDelaysWithoutReloadFacts() throws Exception {
        var h = new Harness(); assertEquals(6_000_000, h.due("a")); assertEquals(5_500_000, h.due("b"));
        h.until(5_499_999); assertEquals(5, h.magazine("a")); assertEquals(5, h.magazine("b"));
        h.until(5_500_000); assertEquals(5, h.magazine("a")); assertEquals(7, h.magazine("b"));
        h.until(6_000_000); assertEquals(7, h.magazine("a")); assertEquals(12_000_000, h.due("a"));
        h.until(18_000_000); assertEquals(10, h.magazine("a")); assertEquals(10, h.magazine("b"));
        assertEquals(25, h.ammo("a").reserve().orElseThrow().rounds()); assertEquals(25, h.ammo("b").reserve().orElseThrow().rounds());
        int cues = h.cues.size(); h.until(60_000_000); assertEquals(cues, h.cues.size()); assertEquals(0, h.reloads()); assertEquals(2, h.state().timers().size());
    }
    @Test void quarterMagazineRoundsUpForSmallAndLargeCapacitiesAndNeverChangesBaseCapacity() throws Exception {
        for (int capacity : List.of(1, 2, 3, 4, 5, 7, 10, 100)) {
            var h = new Harness(capacity, 0, OptionalInt.of(1000)); h.until(6_000_000);
            for (String weapon : List.of("a", "b")) {
                assertEquals((capacity + 3) / 4, h.magazine(weapon)); assertEquals(capacity, h.program.ammoCapacity(h.state(), weapon).capacity());
            }
            h.until(48_000_000); assertEquals(capacity * 2, h.magazine("a")); assertEquals(1000 - capacity * 2, h.ammo("a").reserve().orElseThrow().rounds());
        }
    }
    @Test void onlyAcceptedOwnWeaponFireResetsTheClockAndOneTimerRemainsPerSource() throws Exception {
        var h = new Harness(); h.until(5_000_000); assertEquals(WeaponFire.Outcome.ACCEPTED, h.fire()); assertEquals(11_000_000, h.due("a"));
        h.until(5_100_000); assertEquals(WeaponFire.Outcome.COOLDOWN, h.fire()); assertEquals(11_000_000, h.due("a"));
        h.until(6_000_000); assertEquals(4, h.magazine("a")); assertEquals(7, h.magazine("b"));
        h.equip(pair("normal", "enhanced", "secondary")); h.until(8_000_000); h.fire(); assertEquals(13_500_000, h.due("b")); assertEquals(11_000_000, h.due("a"));
        h.until(11_000_000); assertEquals(6, h.magazine("a")); assertEquals(6, h.magazine("b")); assertEquals(2, h.state().timers().size());
        var empty = new Harness(5, 0, OptionalInt.of(0)); empty.until(1_000_000); assertEquals(WeaponFire.Outcome.NO_AMMUNITION, empty.fire()); assertEquals(6_000_000, empty.due("a"));
    }
    @Test void dueRefillSettlesBeforeFireAtExactlyTheSameTimestamp() throws Exception {
        var h = new Harness(5, 0, OptionalInt.of(30));
        h.session.start(6_000_000, new WeaponFire.Request("player", "at-boundary").signal());
        assertEquals(1, h.magazine("a")); assertEquals(28, h.ammo("a").reserve().orElseThrow().rounds());
        assertEquals(6_000_000, h.state().shots().get("a").firedAt()); assertEquals(12_000_000, h.due("a"));
    }
    @Test void stowAndActualManualReloadKeepThePriorAcceptedFireDeadline() throws Exception {
        var h = new Harness(); h.fire(); h.until(2_000_000); h.reload(); h.until(2_200_000); assertEquals(1, h.reloads()); assertEquals(5, h.magazine("a"));
        h.equip(pair("normal", "enhanced", "secondary")); assertEquals(6_000_000, h.due("a"));
        h.until(6_000_000); assertEquals(7, h.magazine("a")); assertEquals(27, h.ammo("a").reserve().orElseThrow().rounds()); assertEquals(1, h.reloads());
    }
    @Test void emptyAndUnlimitedReservesKeepCadenceAndLaterReserveGainFeedsTheNextPulse() throws Exception {
        var finite = new Harness(5, 0, OptionalInt.of(1)); finite.until(6_000_000); assertEquals(1, finite.magazine("a")); assertEquals(0, finite.ammo("a").reserve().orElseThrow().rounds());
        finite.until(12_000_000); finite.reserve("a", 5); assertEquals(1, finite.magazine("a")); assertEquals(18_000_000, finite.due("a"));
        finite.until(18_000_000); assertEquals(3, finite.magazine("a")); assertEquals(3, finite.ammo("a").reserve().orElseThrow().rounds());
        var unlimited = new Harness(5, 0, OptionalInt.empty()); unlimited.until(30_000_000); assertEquals(10, unlimited.magazine("a")); assertTrue(unlimited.ammo("a").reserve().isEmpty());
    }
    @Test void eachPulseReadsCurrentBaseCapacityAndShrinkingItDoesNotTrimExistingOverflow() throws Exception {
        var h = new Harness(); h.until(6_000_000); assertEquals(7, h.magazine("a"));
        h.session.start(h.now(), SourceChange.bind(new EffectSource("capacity", "test:extended_mag", "player", new BuffInstance.Origin("player", "capacity", "a", ""), Set.of())));
        h.until(12_000_000); assertEquals(10, h.magazine("a")); assertEquals(9, h.program.ammoCapacity(h.state(), "a").capacity());
        h.until(18_000_000); assertEquals(13, h.magazine("a")); h.session.start(h.now(), SourceChange.remove("capacity"));
        h.until(24_000_000); assertEquals(13, h.magazine("a")); assertEquals(5, h.program.ammoCapacity(h.state(), "a").capacity()); assertEquals(22, h.ammo("a").reserve().orElseThrow().rounds());
    }
    @Test void perkReplacementAndPhysicalTransferCancelOldTimersWithoutResettingAmmoBalances() throws Exception {
        var h = new Harness(); h.until(5_000_000); h.equip(pair("enhanced", "none", "primary")); assertEquals(1, h.state().timers().size()); assertEquals(10_500_000, h.due("a"));
        h.until(6_000_000); assertEquals(5, h.magazine("a")); h.until(10_500_000); assertEquals(7, h.magazine("a"));
        h.until(11_000_000); h.equip(Loadout.EMPTY); assertTrue(h.state().timers().isEmpty());
        h.session.start(h.now(), new EquipmentChange("other", Loadout.EMPTY, new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", "normal"))), Optional.of("test:primary"))).signal());
        h.until(12_000_000);
        h.session.start(h.now(), new RuleEngine.Signal(WeaponFire.ACCEPTED, new EffectEvent("player", "", new BuffInstance.Origin("player", "stale", "a", ""), Set.of(), Map.of())));
        assertEquals(17_000_000, h.due("a")); h.until(17_000_000); assertEquals(9, h.magazine("a"));
        assertEquals("other", h.ammo("a").capacityProfile().orElseThrow().holder()); assertEquals(1, h.state().timers().size());
    }
    @Test void checkpointRestoresTheSameFutureCadenceWithoutReplayingAttachmentsOrTransfers() throws Exception {
        var a = new Harness(); var b = new Harness(); for (var h : List.of(a, b)) h.until(8_000_000);
        var aIdentity = a.session.timelineIdentity(); var bIdentity = b.session.timelineIdentity(); assertNotEquals(aIdentity, bIdentity);
        int before = b.cues.size(); b.restore(); assertEquals(before, b.cues.size()); assertEquals(bIdentity, b.session.timelineIdentity());
        assertEquals(a.session.checkpoint().program(), b.session.checkpoint().program());
        assertEquals(a.session.checkpoint().timeline(), b.session.checkpoint().timeline());
        assertEquals(a.session.checkpoint().resourceRouting(), b.session.checkpoint().resourceRouting());
        for (var h : List.of(a, b)) { h.until(11_000_000); h.until(12_000_000); h.until(30_000_000); }
        assertEquals(a.cues, b.cues); assertEquals(a.session.checkpoint().program(), b.session.checkpoint().program());
        assertEquals(a.session.checkpoint().timeline(), b.session.checkpoint().timeline());
        assertEquals(a.session.checkpoint().resourceRouting(), b.session.checkpoint().resourceRouting());
        assertEquals(aIdentity, a.session.timelineIdentity()); assertEquals(bIdentity, b.session.timelineIdentity());
    }
    @Test void unknownPostRefillWorldResultRetainsTheTransferAndCannotReplayIt() throws Exception {
        var h = new Harness(); h.failRefill = true; assertThrows(IllegalStateException.class, () -> h.until(6_000_000));
        assertEquals(7, h.magazine("a")); assertEquals(28, h.ammo("a").reserve().orElseThrow().rounds()); int cues = h.cues.size();
        assertThrows(IllegalStateException.class, () -> h.until(12_000_000)); assertEquals(cues, h.cues.size()); assertEquals(7, h.magazine("a"));
        assertThrows(IllegalStateException.class, h.session::checkpoint);
    }
}
