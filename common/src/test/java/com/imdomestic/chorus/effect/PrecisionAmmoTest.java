package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class PrecisionAmmoTest {
    static final String TT = "triple_tap", FT = "fourth_times_the_charm", D = "chorus_d2:";
    static final Set<String> PRECISION = Set.of("chorus:weapon_damage", "chorus:precision");
    static BuffInstance.Origin origin(String owner, String weapon) { return new BuffInstance.Origin(owner, "shot", weapon, ""); }
    static Loadout pair(String tt, String ft, String drawn) {
        return new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of(TT, tt, FT, ft)),
                "test:secondary", new Loadout.Gear("b", "test:rifle", Map.of(TT, "enhanced", FT, "enhanced"))), Optional.of("test:" + drawn));
    }
    static class Harness {
        final CompiledEffects program; EffectSession session; int sequence;
        Harness(String tt, String ft) throws Exception { this(tt, ft, 0); }
        Harness(String tt, String ft, int magazine) throws Exception {
            program = link(TT, FT, "precision_ammo_weapon");
            var initial = EffectState.empty();
            for (String w : List.of("a", "b")) initial = initial.withAmmo(new AmmoState(w, magazine, 12, Optional.of(new AmmoState.Reserve(30, 30)), Optional.of(new AmmoState.CapacityProfile("player", "test:magazine"))));
            session = new EffectSession(program, EffectCheckpointTest.clock(), 1, initial, _ -> { throw new AssertionError("Unexpected world action"); });
            equip(pair(tt, ft, "primary"));
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void until(long time) { session.observe(time, List.of()); }
        void equip(Loadout next) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), next).signal()); }
        int magazine(String weapon) { return state().ammunition().get(weapon).magazine(); }
        Optional<BuffInstance> buff(String perk, String kind, String weapon) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(D + perk + "_" + kind) && b.origin().weapon().equals(weapon)).findFirst(); }
        double count(String perk, String weapon) { return buff(perk, "counter", weapon).map(b -> b.components().numbers().get("hits")).orElse(0.0); }
        Set<String> receipts(String perk, String weapon) { return buff(perk, "receipts", weapon).map(b -> b.components().sets().get("damage_ids")).orElse(Set.of()); }
        void hit(String weapon) { hit("player", weapon, "hit/" + ++sequence, PRECISION, DamageReceipt.Outcome.APPLIED); }
        void hit(String owner, String weapon, String id, Set<String> tags, DamageReceipt.Outcome outcome) {
            session.observe(now(), DamageFacts.from(new DamageCommand("enemy", origin(owner, weapon), 1, "minecraft:generic", tags, Set.of(), false),
                    new DamageReceipt(id, outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? 1 : 0, Optional.empty(), false)));
        }
        void restore() {
            var saved = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint()));
            session = EffectSession.restore(new CompiledEffects(saved.program()), EffectCheckpointTest.clock(), 256, saved, _ -> { throw new AssertionError("Restore must not issue a world action"); });
        }
    }
    @Test void bothPerksCountIndependentlyAndCanRepeatOnTheSameWeaponForBothVariants() throws Exception {
        for (String variant : List.of("normal", "enhanced")) {
            var h = new Harness(variant, variant);
            for (int i = 1; i <= 12; i++) {
                h.until(i * 100_000L); h.hit("a");
                assertEquals(i / 3 + 2 * (i / 4), h.magazine("a"));
                assertEquals(i % 3, h.count(TT, "a")); assertEquals(i % 4, h.count(FT, "a"));
                assertEquals(0, h.magazine("b")); assertEquals(30, h.state().ammunition().get("a").reserve().orElseThrow().rounds());
            }
        }
    }
    @Test void gapRefreshesPerHitRatherThanFromFirstHitAndStowPreservesBothClocks() throws Exception {
        var h = new Harness("normal", "normal"); h.hit("a"); h.hit("b");
        h.equip(pair("normal", "normal", "secondary")); h.until(1_900_000); h.hit("a"); h.hit("b");
        h.until(3_800_000); h.hit("a"); h.hit("b"); assertEquals(1, h.magazine("a")); assertEquals(1, h.magazine("b"));
        h.until(6_300_000); h.hit("a"); h.hit("b");
        assertEquals(1, h.count(FT, "a")); assertEquals(0, h.count(FT, "b")); assertEquals(3, h.magazine("b"));
    }
    @Test void exactGapBoundaryExpiresBeforeIncomingHitAndDuplicateDoesNotRefreshIt() throws Exception {
        for (String variant : List.of("normal", "enhanced")) {
            var h = new Harness(variant, "none"); long duration = variant.equals("normal") ? 2_000_000 : 3_000_000;
            h.hit("player", "a", "same", PRECISION, DamageReceipt.Outcome.APPLIED);
            h.until(duration - 1); h.hit("player", "a", "same", PRECISION, DamageReceipt.Outcome.APPLIED);
            assertEquals(1, h.count(TT, "a")); assertEquals(duration, h.buff(TT, "counter", "a").orElseThrow().deadline());
            h.until(duration); h.hit("a"); assertEquals(1, h.count(TT, "a")); assertEquals(0, h.magazine("a"));
        }
    }
    @Test void duplicateReceiptAfterThresholdCannotStartNextCycleButDistinctHitsCan() throws Exception {
        var h = new Harness("normal", "normal");
        for (int i = 1; i <= 4; i++) h.hit("player", "a", "id/" + i, PRECISION, DamageReceipt.Outcome.APPLIED);
        assertEquals(3, h.magazine("a"));
        for (int i = 1; i <= 4; i++) h.hit("player", "a", "id/" + i, PRECISION, DamageReceipt.Outcome.APPLIED);
        assertEquals(1, h.count(TT, "a")); assertEquals(0, h.count(FT, "a")); assertEquals(3, h.magazine("a"));
        h.hit("a"); h.hit("a"); assertEquals(4, h.magazine("a"));
    }
    @Test void bodyOtherOwnerOtherWeaponExplosiveAndUnconfirmedDamageDoNotCountOrReset() throws Exception {
        var h = new Harness("normal", "normal"); h.hit("a");
        h.hit("player", "a", "body", Set.of("chorus:weapon_damage"), DamageReceipt.Outcome.APPLIED);
        h.hit("other", "a", "owner", PRECISION, DamageReceipt.Outcome.APPLIED);
        h.hit("player", "missing", "weapon", PRECISION, DamageReceipt.Outcome.APPLIED);
        h.hit("player", "a", "explosion", Set.of("chorus:weapon_damage", "chorus:precision", "chorus:explosive_perk_damage"), DamageReceipt.Outcome.APPLIED);
        for (var outcome : List.of(DamageReceipt.Outcome.IMMUNE, DamageReceipt.Outcome.BLOCKED, DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.FAILED))
            h.hit("player", "a", outcome.name(), PRECISION, outcome);
        assertEquals(1, h.count(TT, "a")); assertEquals(1, h.receipts(TT, "a").size());
        h.hit("b"); assertEquals(1, h.count(TT, "b")); assertEquals(1, h.count(TT, "a"));
    }
    @Test void baseCapacityClampsGenerationWithoutSpendingReservesOrDestroyingOverflow() throws Exception {
        for (int initial : List.of(11, 12, 18)) {
            var h = new Harness("normal", "normal", initial); for (int i = 0; i < 4; i++) h.hit("a");
            assertEquals(Math.max(12, initial), h.magazine("a")); assertEquals(0, h.count(FT, "a"));
            assertEquals(30, h.state().ammunition().get("a").reserve().orElseThrow().rounds());
        }
        var h = new Harness("normal", "normal", 12);
        h.session.start(0, SourceChange.bind(new EffectSource("extended", "test:extended_mag", "player", origin("player", "a"), Set.of())));
        for (int i = 0; i < 4; i++) h.hit("a"); assertEquals(14, h.magazine("a"), "effective base capacity differs from original 12 and overflow limits");
    }
    @Test void continuousFireRetainsOnlyRecentReceiptsAndSourceRemovalCancelsTheirCleanup() throws Exception {
        var h = new Harness("normal", "normal");
        for (int i = 0; i < 200; i++) {
            h.until(i * 100_000L); h.hit("a");
            assertTrue(h.receipts(TT, "a").size() <= 30); assertTrue(h.receipts(FT, "a").size() <= 30);
            assertTrue(h.state().timers().size() <= 60);
        }
        h.equip(pair("enhanced", "none", "primary"));
        assertTrue(h.receipts(TT, "a").isEmpty()); assertEquals(0, h.count(TT, "a")); assertTrue(h.state().timers().isEmpty());
        h.hit("a"); h.until(h.now() + 3_000_000); assertTrue(h.state().buffs().instances().isEmpty()); assertTrue(h.state().timers().isEmpty());
    }
    @Test void receiptCleanupAndCountersSurviveCheckpointWithoutDuplicateAmmo() throws Exception {
        var a = new Harness("normal", "normal"); var b = new Harness("normal", "normal");
        for (var h : List.of(a, b)) { h.hit("a"); h.until(1_000_000); h.hit("a"); }
        b.restore(); assertEquals(a.session.checkpoint(), b.session.checkpoint());
        for (var h : List.of(a, b)) { h.until(2_000_000); h.hit("a"); h.until(3_000_000); assertEquals(Set.of("hit/2", "hit/3"), h.receipts(TT, "a")); h.hit("a"); h.until(6_000_000); }
        assertEquals(a.session.checkpoint(), b.session.checkpoint()); assertEquals(3, b.magazine("a")); assertTrue(b.state().timers().isEmpty());
    }
    @Test void forgettingValidatesDeclaredSetsAndPreservesOtherImmutableComponents() throws Exception {
        var original = new BuffComponents(Map.of("n", 4.0), Map.of("ids", Set.of("old", "new")), Map.of("owner", "player"));
        var next = original.forget("ids", "old"); assertEquals(Set.of("new"), next.sets().get("ids")); assertEquals(2, original.sets().get("ids").size());
        assertEquals(original.numbers(), next.numbers()); assertEquals(original.references(), next.references()); assertEquals(next, next.forget("ids", "absent"));
        assertThrows(IllegalArgumentException.class, () -> original.forget("missing", "old"));
        var data = json(TT); var text = data.toString().replace("\"component\":\"damage_ids\"", "\"component\":\"missing\"");
        assertThrows(RuntimeException.class, () -> CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(text)).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("precision_ammo_weapon")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json(FT)).getOrThrow())));
    }
}
