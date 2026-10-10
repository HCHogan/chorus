package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class RollingStormTest {
    static final String BOLT = "chorus_d2:bolt_charge";
    static final BuffInstance.Origin SYSTEM = new BuffInstance.Origin("player", "system", "", "");
    static final class Harness {
        final CompiledEffects program; final EffectSession session;
        final List<DamageCommand> bolts = new ArrayList<>(); int sequence; boolean lethalBolt;
        Harness(boolean enhanced, boolean amplified, boolean reverse) throws Exception {
            var parts = new ArrayList<EffectProgram>();
            for (var name : List.of("rolling_storm_weapon", "rolling_storm", "bolt_charge"))
                parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json(name)).getOrThrow());
            if (reverse) Collections.reverse(parts);
            program = CompiledEffects.link(parts);
            var state = EffectState.empty().withSource(new EffectSource("system", "chorus_d2:bolt_charge_system", "player", SYSTEM, Set.of()));
            if (amplified) state = state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:amplified"), "player", "player", SYSTEM, 1, 1, 1_000_000).store());
            session = new EffectSession(engine(program), state, request -> {
                var command = (DamageCommand) request.command(); bolts.add(command);
                return new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, command.amount(),
                        lethalBolt ? Optional.of(request.id() + "/death") : Optional.empty(), false);
            });
            var gear = new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", enhanced ? "enhanced" : "normal")),
                    "test:secondary", new Loadout.Gear("b", "test:rifle", Map.of("perk", "normal"))), Optional.of("test:primary"));
            session.start(0, new EquipmentChange("player", Loadout.EMPTY, gear).signal());
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        int stacks() { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(BOLT)).mapToInt(BuffInstance::count).sum(); }
        double energy() { var r = state().resources().get(new ResourceState.Key("player", "chorus_d2:melee")); return r == null ? 0 : r.value(); }
        List<RuleEngine.Signal> facts(String owner, String weapon, boolean credit, boolean lethal, DamageReceipt.Outcome outcome) {
            var origin = new BuffInstance.Origin(owner, "shot", weapon, "");
            return DamageFacts.from(new DamageCommand("enemy", origin, 1, "test:damage", Set.of("chorus:weapon_damage"), credit ? Set.of("chorus:weapon_kill") : Set.of(), false),
                    new DamageReceipt("damage/" + ++sequence, outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? 1 : 0,
                            lethal ? Optional.of("death/" + sequence) : Optional.empty(), false));
        }
        // Isolate the perk's kill reaction from Bolt Charge's separate hit counter.
        void kill(String owner, String weapon, boolean credit) { session.observe(now(), facts(owner, weapon, credit, true, DamageReceipt.Outcome.APPLIED).stream().filter(s -> s.type().equals("chorus:kill")).toList()); settled(); }
        void gain(int count) { session.start(now(), new RuleEngine.Signal("chorus_d2:grant_bolt_charge", new EffectEvent("player", "player", SYSTEM, Set.of(), Map.of("stacks", new com.imdomestic.chorus.stat.Measure(count, com.imdomestic.chorus.stat.Unit.COUNT))))); }
        void stow() {
            var before = state().equipment().get("player");
            session.start(now(), new EquipmentChange("player", before, new Loadout(before.slots(), Optional.of("test:secondary"))).signal());
        }
        void ability() { session.start(now(), new RuleEngine.Signal("chorus:hit", new EffectEvent("player", "enemy", SYSTEM, Set.of("chorus:ability_damage"), Map.of()))); }
        void until(long time) { session.observe(time, List.of()); settled(); }
        void settled() { assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty()); }
    }
    @Test void firstAndSubsequentKillsUseEnhancementAndLiveAmplifiedStateInEitherLinkOrder() throws Exception {
        for (boolean enhanced : List.of(false, true)) for (boolean amplified : List.of(false, true)) for (boolean reverse : List.of(false, true)) {
            var h = new Harness(enhanced, amplified, reverse); int base = amplified ? 2 : 1;
            h.kill("player", "a", true); assertEquals(base + (enhanced ? 1 : 0), h.stacks());
            h.kill("player", "a", true); assertEquals(2 * base + (enhanced ? 1 : 0), h.stacks());
            h.until(1_000_000); h.kill("player", "a", true); assertEquals(2 * base + (enhanced ? 1 : 0) + 1, h.stacks());
            assertEquals(h.stacks() * .025, h.energy(), 1e-10);
        }
    }
    @Test void ownerWeaponAndExplicitWeaponKillCreditAllRemainRequired() throws Exception {
        var h = new Harness(true, true, false);
        h.kill("other", "a", true); h.kill("player", "missing", true); h.kill("player", "a", false);
        for (var outcome : List.of(DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.FAILED, DamageReceipt.Outcome.IMMUNE))
            h.session.observe(0, h.facts("player", "a", true, false, outcome));
        assertEquals(0, h.stacks()); assertEquals(0, h.energy());
        h.kill("player", "b", true); assertEquals(2, h.stacks(), "other equipped weapon uses its own normal perk");
    }
    @Test void stowedEquippedWeaponCanFinishItsKillWithoutGrantingForBothWeapons() throws Exception {
        var h = new Harness(true, false, false); h.stow(); h.kill("player", "a", true);
        assertEquals(2, h.stacks()); h.kill("player", "b", true); assertEquals(3, h.stacks());
    }
    @Test void existingChargeFromAnotherProducerSuppressesEnhancedFirstStackAndOverflowStillReturnsEnergy() throws Exception {
        var h = new Harness(true, true, false); h.gain(9); h.kill("player", "a", true);
        assertEquals(10, h.stacks()); assertEquals(.275, h.energy(), 1e-10);
        h.kill("player", "a", true); assertEquals(10, h.stacks()); assertEquals(.325, h.energy(), 1e-10);
    }
    @Test void killThatBootstrapsChargeDoesNotCountItsEarlierHitAndLaterKillsCombineBothSystems() throws Exception {
        var h = new Harness(false, false, false);
        h.session.observe(0, h.facts("player", "a", true, true, DamageReceipt.Outcome.APPLIED)); assertEquals(1, h.stacks());
        h.session.observe(0, h.facts("player", "a", true, false, DamageReceipt.Outcome.APPLIED)); assertEquals(1, h.stacks()); // Capacity 5 needs one hit, then a following hit.
        h.session.observe(0, h.facts("player", "a", true, true, DamageReceipt.Outcome.APPLIED)); assertEquals(3, h.stacks());
        assertEquals(.075, h.energy(), 1e-10); h.settled();
    }
    @Test void dischargeKillsDoNotRegrantWeaponPerkButNewWeaponKillCanStartAnotherCycle() throws Exception {
        var h = new Harness(true, false, false); h.gain(10); h.lethalBolt = true; h.ability(); assertEquals(0, h.stacks());
        h.until(500_000); assertEquals(2, h.bolts.size()); assertEquals(0, h.stacks()); assertEquals(.25, h.energy(), 1e-10);
        h.kill("player", "a", true); assertEquals(2, h.stacks()); assertEquals(.30, h.energy(), 1e-10);
    }
    @Test void removingThePerkBeforeImpactUsesCurrentOwnerBundlePolicy() throws Exception {
        var h = new Harness(true, false, false); var before = h.state().equipment().get("player");
        h.session.start(0, new EquipmentChange("player", before, Loadout.EMPTY).signal());
        h.kill("player", "a", true); assertEquals(0, h.stacks());
    }
}
