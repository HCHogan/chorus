package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeaponReloadTest {
    static CompiledEffects program() throws Exception {
        return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("weapons")).getOrThrow(), load("kill_clip").program()));
    }
    static Loadout.Gear gun(String instance) { return new Loadout.Gear(instance, "test:rifle", Map.of("perk", "kill_clip")); }
    static Loadout pair(String drawn) { return new Loadout(Map.of("test:primary", gun("a"), "test:secondary", gun("b")), Optional.of("test:" + drawn)); }
    static final class Harness {
        final CompiledEffects program; final EffectSession session;
        final List<HealingCommand> heals = new ArrayList<>(); final List<Action.CueCommand> cues = new ArrayList<>();
        final List<EffectState> atHeal = new ArrayList<>(); final List<WeaponReload.Verify> verifications = new ArrayList<>();
        boolean allowed = true, failHeal; int sequence;
        Harness() throws Exception { this(program(), EffectState.empty()); }
        Harness(CompiledEffects program, EffectState initial) {
            this.program = program;
            session = new EffectSession(engine(program), initial, request -> {
                if (request.command() instanceof WeaponReload.Verify query) { verifications.add(query); return new WeaponReload.Verified(query, allowed); }
                if (request.command() instanceof Action.CueCommand cue) { cues.add(cue); return RuleEngine.Empty.INSTANCE; }
                var heal = (HealingCommand) request.command(); heals.add(heal); atHeal.add(state());
                if (failHeal) throw new IllegalStateException("unknown reload reaction outcome");
                return new HealingReceipt("heal/" + heals.size(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        AmmoState ammo(String id) { return state().ammunition().get(id); }
        void equip(Loadout loadout) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), loadout).signal()); }
        void source(boolean present) { session.start(now(), present ? SourceChange.bind(new EffectSource("fast", "test:fast", "player", new BuffInstance.Origin("player", "fast", "", ""), Set.of())) : SourceChange.remove("fast")); }
        WeaponReload.Receipt reload() {
            var request = new WeaponReload.Request("player", "request-" + ++sequence); var before = state(); var result = program.reload(before, request);
            session.start(now(), request.signal()); assertEquals(result.state(), state()); return (WeaponReload.Receipt) result.result();
        }
        void event(String type, String weapon, int rounds) {
            var origin = new BuffInstance.Origin("player", weapon, weapon, "");
            session.start(now(), new RuleEngine.Signal(type, new EffectEvent("player", "target", origin, Set.of("chorus:weapon_kill"), Map.of("rounds", new Measure(rounds, Unit.ROUND)))));
        }
        boolean active(String id, String weapon) { return state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals(id) && b.origin().weapon().equals(weapon)); }
        void until(long time) { session.observe(time, List.of()); }
    }
    @Test void acceptedReloadTransfersAtDeadlineAndQualifiesOnlyItsWeaponPerk() throws Exception {
        var h = new Harness(); assertEquals(WeaponReload.Outcome.EMPTY_HANDS, h.reload().outcome()); h.equip(pair("primary"));
        h.event("chorus:kill", "a", 0); var accepted = h.reload(); assertEquals(WeaponReload.Outcome.ACCEPTED, accepted.outcome());
        assertEquals(1, h.ammo("a").magazine()); assertEquals(12, h.ammo("a").reserve().orElseThrow().rounds());
        var before = h.state(); assertEquals(WeaponReload.Outcome.BUSY, h.reload().outcome()); assertEquals(before, h.state());
        h.until(199_999); assertTrue(h.heals.isEmpty()); assertFalse(h.active("chorus_d2:kill_clip", "a"));
        h.until(200_000); assertEquals(5, h.ammo("a").magazine()); assertEquals(8, h.ammo("a").reserve().orElseThrow().rounds());
        assertEquals(1, h.ammo("b").magazine()); assertEquals(4, h.heals.getFirst().amount());
        assertTrue(h.atHeal.getFirst().reloads().isEmpty()); assertEquals(h.ammo("a"), h.atHeal.getFirst().ammunition().get("a"));
        assertTrue(h.active("chorus_d2:kill_clip", "a")); assertFalse(h.active("chorus_d2:kill_clip", "b"));
        assertEquals(1, h.verifications.size()); assertEquals(WeaponReload.Outcome.FULL, h.reload().outcome());
        h.until(400_000); assertEquals(1, h.heals.size());
    }
    @Test void stowAndRollChangesCancelEvenWhenTheSameInstanceReturnsBeforeTheOldDeadline() throws Exception {
        var h = new Harness(); h.equip(pair("primary")); var first = h.reload().plan().orElseThrow();
        h.until(50_000); h.equip(pair("secondary")); assertTrue(h.state().reloads().isEmpty()); assertFalse(h.state().timers().containsKey(first.timerId()));
        h.equip(pair("primary")); h.until(200_000); assertTrue(h.heals.isEmpty()); assertEquals(1, h.ammo("a").magazine());
        h.reload(); var changed = new Loadout(new TreeMap<>(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", "none")), "test:secondary", gun("b"))), Optional.of("test:primary"));
        h.equip(changed); h.until(400_000); assertTrue(h.verifications.isEmpty());
        h.equip(Loadout.EMPTY); h.equip(pair("primary")); assertEquals(1, h.ammo("a").magazine()); assertEquals(12, h.ammo("a").reserve().orElseThrow().rounds());
    }
    @Test void ordinaryRefillAndReloadWhoseMagazineWasFilledInTheMeantimeDoNotQualify() throws Exception {
        var h = new Harness(); h.equip(pair("primary")); h.event("chorus:kill", "a", 0); h.reload();
        h.event("test:refill", "a", 0); h.until(200_000);
        assertTrue(h.heals.isEmpty()); assertFalse(h.active("chorus_d2:kill_clip", "a")); assertTrue(h.active("chorus_d2:kill_clip_window", "a"));
        assertTrue(h.state().reloads().isEmpty()); assertEquals(8, h.ammo("a").reserve().orElseThrow().rounds());
        assertTrue(h.cues.stream().anyMatch(c -> c.cue().equals("test:reload_cancelled")));
    }
    @Test void acceptedDurationIsFrozenButCapacityIsResolvedAfterSameBoundaryExpiry() throws Exception {
        var h = new Harness(); h.equip(pair("primary")); h.source(true); var plan = h.reload().plan().orElseThrow();
        assertEquals(100_000, plan.dueAt()); assertEquals(.1, plan.duration().value()); assertEquals(.2, plan.input().value());
        assertEquals(1, plan.calculation().orElseThrow().inputs().contributions().size()); h.source(false);
        h.until(99_999); assertEquals(1, h.ammo("a").magazine()); h.until(100_000); assertEquals(5, h.ammo("a").magazine());
        var other = new Harness(); other.equip(pair("primary")); other.event("test:expand", "a", 0); assertEquals(10, other.program.ammoCapacity(other.state(), "a").capacity());
        other.reload(); other.until(200_000); assertEquals(5, other.ammo("a").magazine()); assertEquals(8, other.ammo("a").reserve().orElseThrow().rounds());
        var growing = new Harness(); growing.equip(pair("primary")); growing.reload(); growing.until(50_000); growing.event("test:expand", "a", 0); growing.until(200_000);
        assertEquals(10, growing.ammo("a").magazine()); assertEquals(3, growing.ammo("a").reserve().orElseThrow().rounds());
    }
    @Test void finitePartialAndUnlimitedReloadsPreserveTheirReserveSemantics() throws Exception {
        var setup = new Harness(); setup.equip(pair("primary"));
        var h = new Harness(setup.program, setup.state().withAmmo(setup.ammo("a").reserve(2)));
        h.reload(); h.until(200_000); assertEquals(3, h.ammo("a").magazine()); assertEquals(0, h.ammo("a").reserve().orElseThrow().rounds());
        assertEquals(2, h.heals.getFirst().amount()); assertEquals(WeaponReload.Outcome.NO_RESERVES, h.reload().outcome());
        h.equip(Loadout.EMPTY); h.equip(pair("primary")); assertEquals(3, h.ammo("a").magazine()); assertEquals(0, h.ammo("a").reserve().orElseThrow().rounds());
        var infinite = new Harness(); infinite.equip(new Loadout(Map.of("test:primary", new Loadout.Gear("infinite", "test:primary_ammo", Map.of())), Optional.of("test:primary")));
        infinite.reload(); infinite.until(200_000); assertEquals(5, infinite.ammo("infinite").magazine()); assertTrue(infinite.ammo("infinite").reserve().isEmpty());
        var overflow = new Harness(setup.program, setup.state().withAmmo(setup.ammo("a").magazine(9)));
        assertEquals(WeaponReload.Outcome.FULL, overflow.reload().outcome()); assertEquals(9, overflow.ammo("a").magazine());
    }
    @Test void hostRejectionDoesNotTransferAndUnknownReactionOutcomeCannotReplayReload() throws Exception {
        var h = new Harness(); h.equip(pair("primary")); h.allowed = false; h.reload(); h.until(200_000);
        assertEquals(1, h.ammo("a").magazine()); assertEquals(12, h.ammo("a").reserve().orElseThrow().rounds()); assertTrue(h.heals.isEmpty()); assertTrue(h.state().reloads().isEmpty());
        var failed = new Harness(); failed.equip(pair("primary")); failed.reload(); failed.failHeal = true;
        assertThrows(IllegalStateException.class, () -> failed.until(200_000)); assertEquals(5, failed.ammo("a").magazine()); assertEquals(8, failed.ammo("a").reserve().orElseThrow().rounds());
        assertTrue(failed.state().reloads().isEmpty()); assertTrue(failed.session.state().engine().pending().isPresent());
        assertThrows(IllegalStateException.class, () -> failed.until(300_000)); assertEquals(1, failed.heals.size());
    }
    @Test void ammunitionInitializationIsAtomicAcrossEquipmentSourcesAndTransfersBetweenHolders() throws Exception {
        var h = new Harness(); h.equip(pair("primary")); h.reload(); h.until(200_000);
        var attempted = new EquipmentChange("other", Loadout.EMPTY, new Loadout(Map.of("test:primary", gun("a")), Optional.of("test:primary")));
        assertThrows(IllegalArgumentException.class, () -> h.program.changeEquipment(h.state(), attempted));
        h.equip(Loadout.EMPTY); h.session.start(h.now(), attempted.signal());
        assertEquals(5, h.ammo("a").magazine()); assertEquals(8, h.ammo("a").reserve().orElseThrow().rounds());
        assertEquals("other", h.ammo("a").capacityProfile().orElseThrow().holder());
        var bad = EffectState.empty().withAmmo(new AmmoState("a", 1, 6, Optional.of(new AmmoState.Reserve(12, 20))));
        assertThrows(IllegalArgumentException.class, () -> h.program.changeEquipment(bad, new EquipmentChange("player", Loadout.EMPTY, pair("primary"))));
    }
    @Test void reloadConfigurationRoundtripsAndRejectsMissingReservesUnitsAndUnknownPrototypes() throws Exception {
        var p = program(); assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
        for (String variant : List.of("reserves", "unit", "item", "unknown")) {
            var data = json("weapons"); var weapon = data.getAsJsonArray("weapons").get(0).getAsJsonObject();
            switch (variant) {
                case "reserves" -> weapon.getAsJsonObject("ammunition").remove("reserves");
                case "unit" -> weapon.getAsJsonObject("reload").getAsJsonObject("value").addProperty("unit", "damage");
                case "item" -> weapon.addProperty("item", "test:missing");
                case "unknown" -> weapon.getAsJsonObject("reload").addProperty("typo", true);
                default -> throw new AssertionError();
            }
            assertThrows(RuntimeException.class, () -> CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(), load("kill_clip").program())));
        }
        assertEquals(1, WeaponReload.micros(new Measure(.0000001, Unit.SECOND)));
        assertEquals(333_334, WeaponReload.micros(new Measure(1.0 / 3, Unit.SECOND)));
        assertThrows(RuntimeException.class, () -> WeaponReload.micros(new Measure(0, Unit.SECOND)));
    }
    @Test void anotherWeaponAndCompletionExactlyAtKillWindowExpiryCannotBorrowTheWindow() throws Exception {
        var h = new Harness(); h.equip(pair("primary")); h.event("chorus:kill", "a", 0); h.equip(pair("secondary")); h.reload(); h.until(200_000);
        assertFalse(h.active("chorus_d2:kill_clip", "a")); assertFalse(h.active("chorus_d2:kill_clip", "b")); assertTrue(h.active("chorus_d2:kill_clip_window", "a"));
        h.equip(pair("primary")); h.until(3_400_000); h.reload(); h.until(3_600_000);
        assertEquals(5, h.ammo("a").magazine()); assertFalse(h.active("chorus_d2:kill_clip", "a")); assertFalse(h.active("chorus_d2:kill_clip_window", "a"));
    }
    @Test void reloadProfileConvertsWeaponStatToSecondsAndInvalidOutputCannotStartATimer() throws Exception {
        var original = program().program();
        for (double atMaximum : List.of(.2, -.4)) {
            var curve = new CalculationProfile("test:reload_curve", "test-1", Unit.STAT_POINT, List.of(
                    new CalculationStep.Clamp("stat_cap", 0, 100),
                    new CalculationStep.Transform("curve", new Curve.Table(new TreeMap<>(Map.of(0.0, .4, 100.0, atMaximum)), Curve.Interpolation.LINEAR, Curve.Boundary.CLAMP), Unit.SECOND)));
            var profiles = new ArrayList<>(original.profiles()); profiles.add(curve);
            var weapons = original.weapons().stream().map(w -> w.item().equals("test:rifle") ? new WeaponDefinition(w.item(), w.ammunition(),
                    new WeaponDefinition.Reload(new Value.Constant(50, Unit.STAT_POINT), Optional.of(curve.id()))) : w).toList();
            var p = new CompiledEffects(new EffectProgram(original.version(), original.buffs(), original.bundles(), profiles, original.defenseProfile(), original.resources(), original.equipment(), original.abilities(), weapons));
            var h = new Harness(p, EffectState.empty()); h.equip(pair("primary")); var before = h.state();
            if (atMaximum > 0) {
                var plan = h.reload().plan().orElseThrow(); assertEquals(Unit.STAT_POINT, plan.input().unit()); assertEquals(.3, plan.duration().value(), 1e-12);
                h.until(plan.dueAt() - 1); assertEquals(1, h.ammo("a").magazine()); h.until(plan.dueAt()); assertEquals(5, h.ammo("a").magazine());
            } else {
                assertThrows(IllegalArgumentException.class, h::reload); assertEquals(before, h.state()); assertTrue(h.state().timers().isEmpty());
            }
        }
    }
}
