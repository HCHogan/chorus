package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class RampageTest {
    static final String PERK = "chorus_d2:rampage";
    static BuffInstance.Origin origin(String owner, String weapon) { return new BuffInstance.Origin(owner, "shot", weapon, ""); }
    static Loadout loadout(String perk, String drawn) {
        return new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", perk)),
                "test:secondary", new Loadout.Gear("b", "test:rifle", Map.of("perk", "enhanced"))), Optional.of("test:" + drawn));
    }
    static class Harness {
        final CompiledEffects program; final EffectSession session; int sequence;
        Harness(boolean enhanced) throws Exception { this(enhanced, false, EffectState.Mode.PVE); }
        Harness(boolean enhanced, boolean reverse, EffectState.Mode mode) throws Exception {
            program = reverse ? link("rampage_weapon", "rampage") : link("rampage", "rampage_weapon");
            session = new EffectSession(engine(program), EffectState.empty().withMode(mode), _ -> { throw new AssertionError("Unexpected world action"); });
            equip(loadout(enhanced ? "enhanced" : "normal", "primary"));
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void equip(Loadout next) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), next).signal()); }
        void until(long time) { session.observe(time, List.of()); assertTrue(session.state().engine().failure().isEmpty()); }
        void kill(String owner, String weapon, Set<String> tags) {
            session.observe(now(), DamageFacts.from(new DamageCommand("enemy", origin(owner, weapon), 1, "minecraft:generic", Set.of("chorus:weapon_damage"), tags, false),
                    new DamageReceipt("hit/" + ++sequence, DamageReceipt.Outcome.APPLIED, 0, 0, 1, Optional.of("death/" + sequence), false)));
        }
        void kill(String weapon) { kill("player", weapon, Set.of("chorus:weapon_kill")); }
        Optional<BuffInstance> buff(String weapon) { return state().buffs().instances().values().stream().filter(b -> b.definition().tags().contains(PERK) && b.origin().weapon().equals(weapon)).findFirst(); }
        int stacks(String weapon) { return buff(weapon).map(BuffInstance::count).orElse(0); }
        DamageCommand attack(String weapon, Set<String> tags) { return new DamageCommand("enemy", origin("player", weapon), 100, "minecraft:generic", tags, Set.of("chorus:weapon_kill"), false, Optional.of("test:weapon_damage")); }
        double damage(String weapon, String... tags) { return program.outgoing(state(), attack(weapon, Set.of(tags)), 100).orElseThrow().output().value(); }
    }
    @Test void threeDamageTiersCapAndEveryKillRefreshesForBothModesAndVariants() throws Exception {
        for (boolean enhanced : List.of(false, true)) for (var mode : EffectState.Mode.values()) {
            var h = new Harness(enhanced, false, mode); long duration = enhanced ? 5_000_000 : 4_500_000;
            for (int i = 0; i < 5; i++) {
                h.until(i * 1_000_000L); h.kill("a");
                assertEquals(Math.min(i + 1, 3), h.stacks("a"));
                assertEquals(new double[]{110, 121, 133.1, 133.1, 133.1}[i], h.damage("a", "chorus:weapon_damage"), 1e-9);
                assertEquals(h.now() + duration, h.buff("a").orElseThrow().deadline());
                assertEquals(100, h.damage("b", "chorus:weapon_damage")); assertEquals(0, h.stacks("b"));
            }
        }
    }
    @Test void sharedTimerDropsExactlyOneStackAtEachExplicitNormalAndEnhancedInterval() throws Exception {
        for (boolean enhanced : List.of(false, true)) {
            var h = new Harness(enhanced); for (int i = 0; i < 3; i++) h.kill("a");
            long interval = enhanced ? 5_000_000 : 4_500_000;
            for (int i = 1; i <= 3; i++) {
                h.until(i * interval - 1); assertEquals(4 - i, h.stacks("a"));
                h.until(i * interval); assertEquals(3 - i, h.stacks("a"));
                assertEquals(new double[]{121, 110, 100}[i - 1], h.damage("a", "chorus:weapon_damage"), 1e-9);
                if (i < 3) assertEquals((i + 1) * interval, h.buff("a").orElseThrow().deadline());
            }
        }
    }
    @Test void killAtDecayBoundaryAddsAfterExpiryAndRestartsTheWholeSharedTimer() throws Exception {
        var h = new Harness(false); for (int i = 0; i < 3; i++) h.kill("a");
        h.until(4_500_000); h.kill("a"); assertEquals(3, h.stacks("a")); assertEquals(9_000_000, h.buff("a").orElseThrow().deadline());
        h.until(9_000_000); assertEquals(2, h.stacks("a"));
        h.until(18_000_000); assertEquals(0, h.stacks("a")); h.kill("a");
        assertEquals(1, h.stacks("a")); assertEquals(22_500_000, h.buff("a").orElseThrow().deadline());
    }
    @Test void creditOwnerAndWeaponStayIndependentAndUnconfirmedDeathsDoNotGrant() throws Exception {
        var h = new Harness(false);
        h.kill("other", "a", Set.of("chorus:weapon_kill")); h.kill("player", "missing", Set.of("chorus:weapon_kill"));
        h.kill("player", "a", Set.of()); h.kill("player", "a", Set.of("chorus:grenade_kill"));
        for (var outcome : List.of(DamageReceipt.Outcome.IMMUNE, DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.FAILED, DamageReceipt.Outcome.APPLIED))
            h.session.observe(0, DamageFacts.from(h.attack("a", Set.of("chorus:weapon_damage")),
                    new DamageReceipt("not-lethal/" + outcome, outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? 1 : 0, Optional.empty(), false)));
        assertEquals(0, h.stacks("a")); h.kill("b"); assertEquals(0, h.stacks("a")); assertEquals(1, h.stacks("b"));
        assertEquals(100, h.damage("b")); assertEquals(100, h.damage("b", "chorus:weapon_damage", "chorus:explosive_perk_damage"));
    }
    @Test void stowKeepsBothIndependentTimersButRemovingOrReplacingAPerkClearsItsVariant() throws Exception {
        var h = new Harness(false); h.kill("a"); h.kill("b"); h.equip(loadout("normal", "secondary"));
        h.until(4_500_000); assertEquals(0, h.stacks("a")); assertEquals(1, h.stacks("b"));
        h.kill("a"); assertEquals(1, h.stacks("a"), "a stowed equipped weapon can finish its own kill");
        h.equip(loadout("enhanced", "secondary")); assertEquals(0, h.stacks("a")); assertEquals(1, h.stacks("b"));
        h.kill("a"); assertEquals(PERK + "_enhanced", h.buff("a").orElseThrow().definition().id());
        assertEquals(110, h.damage("a", "chorus:weapon_damage"), 1e-9, "old normal and new enhanced buffs cannot multiply");
        h.equip(Loadout.EMPTY); h.kill("a"); h.kill("b"); assertTrue(h.state().buffs().instances().isEmpty());
    }
    @Test void capturedDamageRetainsTheOldTierThroughDecayAndPerkRemovalWithoutLiveDoubleCounting() throws Exception {
        var h = new Harness(false); for (int i = 0; i < 3; i++) h.kill("a");
        var snapshot = h.program.captureDamage(h.state(), h.attack("a", Set.of("chorus:weapon_damage")));
        h.until(4_500_000); assertEquals(121, h.damage("a", "chorus:weapon_damage"), 1e-9);
        var result = h.program.outgoing(h.state(), snapshot.command("enemy"), 100).orElseThrow();
        assertEquals(133.1, result.output().value(), 1e-9); assertEquals(1, result.trace().contributions().size());
        h.equip(Loadout.EMPTY); assertEquals(133.1, h.program.outgoing(h.state(), snapshot.command("enemy"), 100).orElseThrow().output().value(), 1e-9);
    }
    @Test void definitionsLinkInEitherOrderAndRoundTripWithoutImplicitDecayDefaults() throws Exception {
        var a = new Harness(false, false, EffectState.Mode.PVE); var b = new Harness(false, true, EffectState.Mode.PVE);
        a.kill("a"); b.kill("a"); assertEquals(a.damage("a", "chorus:weapon_damage"), b.damage("a", "chorus:weapon_damage"));
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, a.program).getOrThrow();
        assertEquals(a.program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        var broken = json("rampage"); broken.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").remove("decay_interval");
        assertTrue(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, broken).error().isPresent());
    }
}
