package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class DemolitionistTest {
    static final String ENERGY = "chorus_d2:arcbolt_energy", SLOT = "chorus_d2:grenade", COOLDOWN = "chorus_d2:demolitionist_cooldown";
    static CompiledEffects program() throws Exception {
        var parts = new ArrayList<EffectProgram>();
        for (String fixture : List.of("demolitionist", "character_stats", "arcbolt_energy", "demolitionist_weapon", "kill_clip", "clown_cartridge")) {
            var data = JsonParser.parseString(json(fixture).toString().replace("\"test-1\"", "\"compendium-2026-10-05\""));
            parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
        }
        return CompiledEffects.link(parts);
    }
    static Loadout pair(String drawn) { return new Loadout(Map.of(
            "test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", "normal", "reload_perk", "kill_clip")),
            "test:secondary", new Loadout.Gear("b", "test:shotgun", Map.of("perk", "enhanced", "reload_perk", "clown"))), Optional.of("test:" + drawn)); }
    static class Harness {
        final CompiledEffects program = program(); final EffectSession session; final List<EffectState> atHeal = new ArrayList<>(); boolean fail; int sequence;
        Harness(int magazine, int reserves) throws Exception {
            var initial = EffectState.empty().withResource(new ResourceState(new ResourceState.Key("player", ENERGY), 0, 1, 0));
            for (String id : List.of("a", "b")) initial = initial.withAmmo(new AmmoState(id, magazine, 5, Optional.of(new AmmoState.Reserve(reserves, 30))));
            initial = initial.withSource(new EffectSource("input", "test:demo_inputs", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of()));
            session = new EffectSession(engine(program), initial, request -> {
                if (request.command() instanceof WeaponReload.Verify check) return new WeaponReload.Verified(check, true);
                var heal = (HealingCommand) request.command(); atHeal.add(state()); if (fail) throw new IllegalStateException("Unknown grenade world result");
                return new HealingReceipt("heal/" + atHeal.size(), heal, HealingReceipt.Outcome.APPLIED, 1, 1, 0);
            });
            equip(pair("primary")); select("free_grenade");
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        AmmoState ammo(String id) { return state().ammunition().get(id); }
        double energy() { return state().resources().get(new ResourceState.Key("player", ENERGY)).value(); }
        void equip(Loadout loadout) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), loadout).signal()); }
        void select(String ability) { session.start(now(), new AbilityChange("player", state().abilities().getOrDefault("player", AbilityLoadout.EMPTY), new AbilityLoadout(Map.of(SLOT, "test:" + ability))).signal()); }
        AbilityUse.Receipt use() {
            var request = new AbilityUse.Request("player", SLOT, "cast-" + ++sequence, new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of()));
            var planned = (AbilityUse.Receipt) program.useAbility(state(), request).result(); session.start(now(), request.signal()); return planned;
        }
        void send(String type, String target, Map<String, Measure> numbers) { session.start(now(), new RuleEngine.Signal(type, new EffectEvent("player", target, new BuffInstance.Origin("player", "input", "", ""), Set.of(), numbers))); }
        void spend(String weapon, int rounds) { send("test:spend", weapon, Map.of("rounds", new Measure(rounds, Unit.ROUND))); }
        void stat(int points) { send("test:stat", "player", Map.of("stat", new Measure(points, Unit.STAT_POINT))); }
        void kill(String owner, String weapon, boolean credit) { session.start(now(), new RuleEngine.Signal("chorus:kill", new EffectEvent(owner, "victim", new BuffInstance.Origin(owner, weapon, weapon, ""), credit ? Set.of("chorus:weapon_kill") : Set.of(), Map.of()))); }
        long cooldown(String weapon) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(COOLDOWN) && b.origin().weapon().equals(weapon)).mapToLong(BuffInstance::deadline).findFirst().orElse(0); }
        void until(long time) { session.observe(time, List.of()); }
    }
    @Test void weaponKillUsesGrenadeStatAndRecipientScalarWithInstanceAndCreditIsolation() throws Exception {
        var h = new Harness(1, 12); h.stat(100);
        h.kill("other", "a", true); h.kill("player", "a", false); assertEquals(0, h.energy());
        h.kill("player", "a", true); assertEquals(.0675, h.energy(), 1e-12);
        h.kill("player", "b", true); assertEquals(.0675 + .1485, h.energy(), 1e-12, "stowed enhanced shotgun receives its own .088 base");
        assertEquals(1, h.ammo("a").magazine()); assertEquals(0, h.cooldown("a"), "energy leg has no refill cooldown");
    }
    @Test void heldWeaponRefillsFromReservesBeforeAbilityBodyWithoutReloadPerkTriggers() throws Exception {
        var h = new Harness(1, 12); h.kill("player", "a", true); var random = h.state().random();
        h.use(); assertEquals(5, h.ammo("a").magazine()); assertEquals(8, h.ammo("a").reserve().orElseThrow().rounds()); assertEquals(1, h.ammo("b").magazine());
        assertEquals(5, h.atHeal.getFirst().ammunition().get("a").magazine()); assertEquals(3_000_000, h.cooldown("a"));
        assertTrue(h.state().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("chorus_d2:kill_clip")));
        h.equip(pair("secondary")); h.use(); assertEquals(5, h.ammo("b").magazine()); assertEquals(random, h.state().random(), "Clown Cartridge does not sample a refill");
    }
    @Test void cooldownIsThreeSecondsPerWeaponAndStowOrReequipDoesNotResetIt() throws Exception {
        var h = new Harness(1, 12); h.use(); h.spend("a", 2);
        h.equip(pair("secondary")); h.use(); h.equip(Loadout.EMPTY); h.equip(pair("primary"));
        h.until(2_999_999); h.use(); assertEquals(3, h.ammo("a").magazine()); assertEquals(3_000_000, h.cooldown("a"));
        h.until(3_000_000); h.use(); assertEquals(5, h.ammo("a").magazine()); assertEquals(6_000_000, h.cooldown("a"));
    }
    @Test void noTransferDoesNotStartCooldownAndPartialReservesDoNotCreateAmmo() throws Exception {
        var full = new Harness(5, 12); full.use(); assertEquals(0, full.cooldown("a")); full.spend("a", 1); full.use(); assertEquals(5, full.ammo("a").magazine());
        var empty = new Harness(1, 0); empty.use(); assertEquals(1, empty.ammo("a").magazine()); assertEquals(0, empty.cooldown("a"));
        var partial = new Harness(1, 2); partial.use(); assertEquals(3, partial.ammo("a").magazine()); assertEquals(0, partial.ammo("a").reserve().orElseThrow().rounds()); assertEquals(3_000_000, partial.cooldown("a"));
        var overflow = new Harness(7, 12); overflow.use(); assertEquals(7, overflow.ammo("a").magazine()); assertEquals(12, overflow.ammo("a").reserve().orElseThrow().rounds()); assertEquals(0, overflow.cooldown("a"));
    }
    @Test void rejectedOrNonGrenadeUsesCannotRefillWhileAcceptedFreeGrenadesCan() throws Exception {
        var h = new Harness(1, 12); h.select("blocked_grenade"); assertEquals(AbilityUse.Outcome.CONDITION, h.use().outcome());
        h.select("grenade"); assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.use().outcome());
        h.select("not_grenade"); h.use(); assertEquals(1, h.ammo("a").magazine()); assertEquals(0, h.cooldown("a"));
        h.select("free_grenade"); assertEquals(0, h.use().cost().orElseThrow().receipt().paid()); assertEquals(5, h.ammo("a").magazine());
    }
    @Test void laterUnknownWorldOutcomeKeepsAcceptedRefillAndCooldownWithoutReplaying() throws Exception {
        var h = new Harness(1, 12); h.fail = true; assertThrows(IllegalStateException.class, h::use);
        assertEquals(5, h.ammo("a").magazine()); assertEquals(8, h.ammo("a").reserve().orElseThrow().rounds()); assertEquals(3_000_000, h.cooldown("a"));
        assertEquals(1, h.atHeal.size()); assertFalse(h.session.state().engine().pending().isEmpty());
    }
    @Test void refillDuringPendingManualReloadDoesNotProduceAFalseCompletion() throws Exception {
        var h = new Harness(1, 12); h.kill("player", "a", true);
        h.session.start(0, new WeaponReload.Request("player", "reload").signal()); assertFalse(h.state().reloads().isEmpty());
        h.use(); h.until(200_000); assertTrue(h.state().reloads().isEmpty());
        assertEquals(5, h.ammo("a").magazine()); assertEquals(8, h.ammo("a").reserve().orElseThrow().rounds());
        assertTrue(h.state().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("chorus_d2:kill_clip")));
    }
    @Test void grenadePassiveAndGainCurvesUsePublishedValuesAndRoundTripWithContent() throws Exception {
        var h = new Harness(1, 12); h.stat(70); h.until(1_000_000);
        assertEquals(2.55660968 / 151.5, h.energy(), 1e-12);
        double before = h.energy(); h.kill("player", "a", true); assertEquals(.04 * .75 * (1 + .9923657826827956), h.energy() - before, 1e-12);
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, h.program).getOrThrow();
        assertEquals(h.program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
    }
}
