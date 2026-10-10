package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Partial Compendium content: known center damage; spatial discharge envelope remains uncalibrated. */
class BoltChargeTest {
    static final String BOLT = "chorus_d2:bolt_charge", COUNTER = "chorus_d2:bolt_charge_counter", MELEE = "chorus_d2:melee";
    static final BuffInstance.Origin SYSTEM = new BuffInstance.Origin("player", "arc", "unregistered-initial-source", "");
    static final class Harness {
        final CompiledEffects program; final EffectSession session; final List<DamageCommand> bolts = new ArrayList<>();
        int sequence; boolean unknown; double health = 1000;
        Harness() throws Exception { this(EffectState.Mode.PVE); }
        Harness(EffectState.Mode mode) throws Exception { this(load("bolt_charge"), EffectState.empty().withMode(mode)); }
        Harness(CompiledEffects program, EffectState state) {
            this.program = program;
            state = state.withSource(new EffectSource("arc", "chorus_d2:bolt_charge_system", "player", SYSTEM, Set.of("chorus:sword")));
            for (var e : Map.of("gun", 20, "small", 1, "sword", 50, "gl", 10, "sniper", 3).entrySet())
                if (!state.ammunition().containsKey(e.getKey())) state = state.withAmmo(new AmmoState(e.getKey(), 999, e.getValue(), Optional.empty()));
            session = new EffectSession(engine(program), state, request -> {
                var command = (DamageCommand) request.command(); bolts.add(command);
                if (unknown) throw new IllegalStateException("Unknown discharge component outcome");
                double loss = Math.min(health, command.amount()); health -= loss;
                return new DamageReceipt(request.id().toString(), loss == 0 ? DamageReceipt.Outcome.FAILED : DamageReceipt.Outcome.APPLIED, 0, 0, loss, Optional.empty(), false);
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        Optional<BuffInstance> buff(String id) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(id)).findFirst(); }
        int stacks() { return buff(BOLT).map(BuffInstance::count).orElse(0); }
        double hits() { return buff(COUNTER).map(b -> b.components().numbers().get("hits")).orElse(0.0); }
        double threshold() { return buff(COUNTER).orElseThrow().components().numbers().get("threshold"); }
        double energy() { var r = state().resources().get(new ResourceState.Key("player", MELEE)); return r == null ? 0 : r.value(); }
        void gain(int stacks) { signal(0, "chorus_d2:grant_bolt_charge", new EffectEvent("player", "player", SYSTEM, Set.of(), Map.of("stacks", new Measure(stacks, Unit.COUNT)))); }
        void signal(long time, String type, EffectEvent e) { session.start(time, new RuleEngine.Signal(type, e)); }
        List<RuleEngine.Signal> facts(String weapon, Set<String> tags, Set<String> sourceTags, String batch, ProcPolicy proc, DamageReceipt.Outcome outcome, double amount) {
            var origin = new BuffInstance.Origin("player", weapon, weapon, "", sourceTags);
            var command = new DamageCommand("enemy", origin, 2, "minecraft:generic", tags, Set.of(), false).withProc(proc);
            if (batch != null) command = command.withBatch(new DamageBatch(batch, "player"));
            return DamageFacts.from(command, new DamageReceipt("input-" + ++sequence, outcome, 0, 0, amount, Optional.empty(), false));
        }
        void weapon(long time, String weapon, String category) { session.observe(time, facts(weapon, Set.of("chorus:weapon_damage"), category.isEmpty() ? Set.of() : Set.of(category), null, ProcPolicy.ALLOW, DamageReceipt.Outcome.APPLIED, 2)); }
        void ability(long time) { hit(time, Set.of("chorus:ability_damage"), ProcPolicy.ALLOW); }
        void hit(long time, Set<String> tags, ProcPolicy proc) { session.observe(time, facts("", tags, Set.of(), null, proc, DamageReceipt.Outcome.APPLIED, 2)); }
    }
    @Test void weaponDamageCannotBootstrapTheFirstStack() throws Exception {
        var h = new Harness(); for (int i = 0; i < 10; i++) h.weapon(0, "small", "");
        assertEquals(0, h.stacks()); assertEquals(0, h.hits()); assertEquals(0, h.energy());
    }
    @Test void magazineThresholdArmsOnlyTheFollowingDamageAndReadsTheActualEventWeapon() throws Exception {
        var h = new Harness(); h.gain(1);
        for (int i = 0; i < 4; i++) h.weapon(0, "gun", "");
        assertEquals(4, h.threshold()); assertEquals(4, h.hits()); assertEquals(1, h.stacks());
        h.hit(0, Set.of("test:generic"), ProcPolicy.ALLOW); assertEquals(2, h.stacks()); assertEquals(0, h.hits()); assertEquals(.05, h.energy(), 1e-10);
    }
    @Test void swordGrenadeLauncherAndThreeRoundSniperUseTheirExplicitCases() throws Exception {
        for (var entry : Map.of("sword", "chorus:sword", "gl", "chorus:grenade_launcher", "sniper", "chorus:sniper").entrySet()) {
            var h = new Harness(); h.gain(1); h.weapon(0, entry.getKey(), entry.getValue());
            int expected = entry.getKey().equals("sniper") ? 1 : 2; assertEquals(expected, h.threshold());
            if (expected == 2) h.weapon(0, entry.getKey(), entry.getValue());
            assertEquals(1, h.stacks()); h.ability(0); assertEquals(2, h.stacks());
        }
    }
    @Test void thresholdUsesCapacityNotOverflowRoundsOrTheOriginalGrantSourcesTags() throws Exception {
        var h = new Harness(); h.gain(1); h.weapon(0, "gun", "");
        assertEquals(999, h.state().ammunition().get("gun").magazine()); assertEquals(4, h.threshold()); // Bound source is tagged sword.
        var data = json("bolt_charge"); data.add("profiles", JsonParser.parseString("[{\"id\":\"test:capacity\",\"version\":\"test-bolt-v1\",\"input_unit\":\"round\",\"steps\":[{\"type\":\"chorus:apply\",\"id\":\"size\",\"operation\":\"add\",\"group\":{\"name\":\"size\",\"reduction\":\"sum\"}}]}]"));
        var modifier = JsonParser.parseString("{\"id\":\"size\",\"profile\":\"test:capacity\",\"stage\":\"size\",\"group\":\"size\",\"op\":\"add\",\"stacking_key\":\"test:size\",\"value\":{\"type\":\"chorus:constant\",\"value\":20,\"unit\":\"round\"},\"reference\":\"Synthetic capacity adjustment\",\"confidence\":\"assumed\"}");
        var modifiers = new JsonArray(); modifiers.add(modifier); data.getAsJsonArray("bundles").get(0).getAsJsonObject().add("modifiers", modifiers);
        var dynamic = new Harness(compile(data), EffectState.empty().withAmmo(new AmmoState("gun", 999, 20, Optional.empty(), Optional.of(new AmmoState.CapacityProfile("player", "test:capacity")))));
        dynamic.gain(1); dynamic.weapon(0, "gun", ""); assertEquals(7, dynamic.threshold());
    }
    @Test void fiveSecondWindowRefreshesOnWeaponProgressAndExpiresBeforeAnExactBoundaryHit() throws Exception {
        var h = new Harness(); h.gain(1); h.weapon(0, "gun", ""); h.weapon(4_999_999, "gun", ""); assertEquals(2, h.hits());
        h.weapon(9_999_999, "gun", ""); assertEquals(1, h.hits()); assertEquals(1, h.stacks());
        var ready = new Harness(); ready.gain(1); ready.weapon(0, "small", ""); ready.ability(5_000_000); assertEquals(1, ready.stacks());
    }
    @Test void weaponSwitchClearsOnlyTheHitCounterAndForeignSwitchDoesNot() throws Exception {
        var h = new Harness(); h.gain(1); h.weapon(0, "gun", "");
        var other = new BuffInstance.Origin("other", "other", "other", "");
        h.signal(0, "chorus:weapon_stowed", new EffectEvent("other", "", other, Set.of(), Map.of())); assertEquals(1, h.hits());
        h.signal(0, "chorus:weapon_stowed", new EffectEvent("player", "", new BuffInstance.Origin("player", "gun", "gun", ""), Set.of(), Map.of()));
        assertEquals(0, h.hits()); assertEquals(1, h.stacks()); h.ability(0); assertEquals(1, h.stacks());
    }
    @Test void simultaneousComponentsGrantAtMostOneStackButIndependentBatchesInTheSameRootStillProgress() throws Exception {
        var h = new Harness(); h.gain(1); var facts = new ArrayList<RuleEngine.Signal>();
        for (int i = 0; i < 8; i++) facts.addAll(h.facts("small", Set.of("chorus:weapon_damage"), Set.of(), "same", ProcPolicy.ALLOW, DamageReceipt.Outcome.APPLIED, 2));
        h.session.observe(0, facts); assertEquals(2, h.stacks());
        h.hit(0, Set.of(), ProcPolicy.ALLOW); assertEquals(3, h.stacks());
        var separate = new Harness(); separate.gain(1); facts.clear();
        for (int i = 0; i < 4; i++) facts.addAll(separate.facts("small", Set.of("chorus:weapon_damage"), Set.of(), null, ProcPolicy.ALLOW, DamageReceipt.Outcome.APPLIED, 2));
        separate.session.observe(0, facts); assertEquals(3, separate.stacks());
    }
    @Test void duplicateArmingReceiptAndZeroLossCannotBecomeTheFollowingDamage() throws Exception {
        var h = new Harness(); h.gain(1); var one = h.facts("small", Set.of("chorus:weapon_damage"), Set.of(), null, ProcPolicy.ALLOW, DamageReceipt.Outcome.APPLIED, 2);
        h.session.observe(0, one); h.session.observe(0, one); assertEquals(1, h.stacks());
        for (var outcome : DamageReceipt.Outcome.values()) if (outcome != DamageReceipt.Outcome.APPLIED)
            h.session.observe(0, h.facts("small", Set.of("chorus:weapon_damage"), Set.of(), null, ProcPolicy.ALLOW, outcome, 0));
        assertEquals(1, h.stacks()); h.ability(0); assertEquals(2, h.stacks());
    }
    @Test void cappedStorageStillReturnsEnergyForEveryCreditedStackIncludingFurtherGainsAtTen() throws Exception {
        var h = new Harness(); h.gain(9); double before = h.energy(); h.gain(4);
        assertEquals(10, h.stacks()); assertEquals(.1, h.energy() - before, 1e-10); h.gain(4); assertEquals(.425, h.energy(), 1e-10);
        h.gain(100); assertEquals(1, h.energy()); assertEquals(10, h.stacks());
    }
    @Test void anyGrantBuffProducerUsesCommittedLifecycleCreditAndDoesNotNeedToReimplementEnergy() throws Exception {
        var data = json("bolt_charge"); var rules = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules");
        rules.add(JsonParser.parseString("{\"id\":\"other-producer\",\"on\":\"test:external-grant\",\"do\":[{\"type\":\"chorus:grant_buff\",\"buff\":\"chorus_d2:bolt_charge\",\"stacks\":{\"type\":\"chorus:constant\",\"value\":4,\"unit\":\"count\"}}]}"));
        var h = new Harness(compile(data), EffectState.empty()); h.gain(9); h.signal(0, "test:external-grant", new EffectEvent("player", "player", SYSTEM, Set.of(), Map.of()));
        assertEquals(10, h.stacks()); assertEquals(.325, h.energy(), 1e-10);
    }
    @Test void dischargeConsumesOnceAtTriggerAndProducesTwoGenericArcComponentsAfterHalfASecond() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = new Harness(mode); h.gain(10); h.ability(0); h.ability(0); assertEquals(0, h.stacks());
            h.session.observe(499_999, List.of()); assertTrue(h.bolts.isEmpty()); h.session.observe(500_000, List.of());
            assertEquals(2, h.bolts.size()); assertEquals(mode == EffectState.Mode.PVE ? 67.5 : 6.6, 1000 - h.health, 1e-8);
            assertEquals(h.bolts.get(0).batch(), h.bolts.get(1).batch()); assertTrue(h.bolts.getFirst().batch().isPresent());
            for (var bolt : h.bolts) { assertTrue(bolt.tags().contains("chorus:arc")); assertFalse(bolt.tags().contains("chorus:ability_damage")); assertTrue(bolt.killTags().isEmpty()); }
        }
    }
    @Test void hitThatGrantsTheTenthStackDoesNotAlsoDischargeIt() throws Exception {
        var h = new Harness(); h.gain(9); h.weapon(0, "small", ""); h.ability(0); assertEquals(10, h.stacks());
        h.session.observe(500_000, List.of()); assertTrue(h.bolts.isEmpty()); h.ability(500_000); assertEquals(0, h.stacks());
        h.session.observe(1_000_000, List.of()); assertEquals(2, h.bolts.size());
    }
    @Test void explicitProcDenialAndBossAutoShatterDoNotConsumeReadyChargeButUnpoweredMeleeDoes() throws Exception {
        var h = new Harness(); h.gain(10); h.hit(0, Set.of("chorus:ability_damage"), new ProcPolicy(Set.of("chorus_d2:bolt_discharge"))); assertEquals(10, h.stacks());
        h.hit(0, Set.of("chorus:freeze_shatter", "chorus:boss_auto_shatter"), ProcPolicy.ALLOW); assertEquals(10, h.stacks());
        h.session.observe(0, h.facts("", Set.of("chorus:unpowered_melee"), Set.of(), null, ProcPolicy.ALLOW, DamageReceipt.Outcome.IMMUNE, 0)); assertEquals(10, h.stacks());
        h.hit(0, Set.of("chorus:unpowered_melee"), ProcPolicy.ALLOW); assertEquals(0, h.stacks());
    }
    @Test void documentedNonAbilityTriggersCanDischargeAndPlainWeaponDamageCannot() throws Exception {
        for (var trigger : List.of("chorus:volatile_explosion", "chorus:freeze_shatter", "chorus:tangle_explosion", "chorus:threadling", "chorus:unravel_thread", "chorus:suspend_boss_snap", "chorus:forerunner_rock")) {
            var h = new Harness(); h.gain(10); h.weapon(0, "gun", ""); assertEquals(10, h.stacks()); h.hit(0, Set.of(trigger), ProcPolicy.ALLOW); assertEquals(0, h.stacks(), trigger);
        }
    }
    @Test void unknownDischargeDoesNotRestoreSpentStacksOrRepeatTheWorldCommand() throws Exception {
        var h = new Harness(); h.gain(10); h.ability(0); h.unknown = true;
        assertThrows(IllegalStateException.class, () -> h.session.observe(500_000, List.of())); assertEquals(0, h.stacks()); assertEquals(1, h.bolts.size()); assertTrue(h.session.state().engine().pending().isPresent());
    }
    @Test void lifecycleFactsExposeReceiptCreditAndBeforeAfterEvenWhenStateIsAlreadyGone() throws Exception {
        var p = load("bolt_charge"); var first = Buffs.grant(EffectState.empty().buffs(), p.buff(BOLT), "player", "player", SYSTEM, 9, 1, Long.MAX_VALUE);
        var overflow = Buffs.grant(first.store(), p.buff(BOLT), "player", "player", SYSTEM, 4, 1, Long.MAX_VALUE);
        var fact = overflow.changes().getFirst().event(); assertEquals(4, fact.numbers().get("credited").value()); assertEquals(1, fact.numbers().get("stored_delta").value());
        assertEquals(9, fact.numbers().get("stacks_before").value()); assertEquals(10, fact.numbers().get("stacks_after").value());
        var ended = Buffs.consume(overflow.store(), overflow.store().instances().keySet().iterator().next(), 10).changes().getLast().event();
        assertEquals(-10, ended.numbers().get("stored_delta").value()); assertEquals(0, ended.numbers().get("stacks_after").value()); assertEquals("consumed", ended.references().get("reason"));
        assertEquals(BOLT, fact.references().get("buff_definition")); assertEquals("player", fact.victim());
        assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
    }
}
