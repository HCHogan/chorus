package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class MarksmanDodgeTest {
    static final String ABILITY="chorus_d2:marksman_dodge", SLOT="chorus_d2:class", ENERGY="chorus_d2:marksman_dodge_energy";
    static CompiledEffects program(boolean calibrated) throws Exception {
        var ability=json("marksman_dodge");
        if(calibrated) ability.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject("reload_delay")
                .add("value",json("marksman_dodge_test_calibration").getAsJsonObject("parameters").get("reload_delay"));
        var weapons=json("weapons");
        weapons.getAsJsonObject("equipment").getAsJsonArray("slots").add(JsonParser.parseString("""
            {"id":"test:heavy","accepts":["test:weapon"],"weapon":true}
            """));
        return CompiledEffects.link(List.of(weapons,json("kill_clip"),ability).stream()
                .map(j->EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,JsonParser.parseString(j.toString().replace("test-1","compendium-2026-10-05"))).getOrThrow()).toList());
    }
    static Loadout equipped(String primary) {
        return new Loadout(Map.of("test:primary",WeaponReloadTest.gun(primary),"test:secondary",WeaponReloadTest.gun("b"),
                "test:heavy",new Loadout.Gear("c","test:primary_ammo",Map.of())),Optional.of("test:primary"));
    }
    static final class Harness {
        final CompiledEffects p; final EffectSession session;
        final List<InstantReload.Check> checks=new ArrayList<>(); final List<HealingCommand> heals=new ArrayList<>(); final List<EffectState> beforeHeals=new ArrayList<>();
        boolean allowed=true; int casts;
        Harness(boolean calibrated) throws Exception {
            p=program(calibrated);session=new EffectSession(engine(p),EffectState.empty(),request->switch(request.command()) {
                case InstantReload.Check check -> {checks.add(check);yield new InstantReload.Checked(check,allowed);}
                case HealingCommand heal -> {heals.add(heal);beforeHeals.add(state());yield new HealingReceipt(request.id().toString(),heal,HealingReceipt.Outcome.APPLIED,heal.amount(),heal.amount(),0);}
                case Action.CueCommand ignored -> RuleEngine.Empty.INSTANCE;
                default -> throw new AssertionError(request.command());
            });
            choose(true);equip(equipped("a"));
        }
        EffectState state(){return session.state().engine().domain();}
        void send(RuleEngine.Signal signal){session.start(state().buffs().timeMicros(),signal);}
        void choose(boolean selected){send(new AbilityChange("player",state().abilities().getOrDefault("player",AbilityLoadout.EMPTY),selected?new AbilityLoadout(Map.of(SLOT,ABILITY)):AbilityLoadout.EMPTY).signal());}
        void equip(Loadout loadout){send(new EquipmentChange("player",state().equipment().getOrDefault("player",Loadout.EMPTY),loadout).signal());}
        AbilityUse.Request request(){return new AbilityUse.Request("player",SLOT,"dodge/"+casts,new EffectEvent("player","player",new BuffInstance.Origin("player","","",""),Set.of(),Map.of()));}
        void cast(){casts++;send(request().signal());}
        int magazine(String weapon){return state().ammunition().get(weapon).magazine();}
        double energy(){return state().resources().get(new ResourceState.Key("player",ENERGY)).value();}
        void until(long time){session.observe(time,List.of());}
    }
    @Test void dodgePaysOnceAndReloadsAllThreeWeaponsAfterTheCalibratedDelay() throws Exception {
        var h=new Harness(true);
        h.send(new RuleEngine.Signal("chorus:kill",new EffectEvent("player","victim",new BuffInstance.Origin("player","b","b",""),Set.of("chorus:weapon_kill"),Map.of())));
        h.cast();assertEquals(0,h.energy());assertTrue(h.checks.isEmpty());h.until(199_999);assertEquals(1,h.magazine("a"));assertEquals(0,h.magazine("c"));
        h.until(200_000);for(String weapon:List.of("a","b","c"))assertEquals(5,h.magazine(weapon));
        assertEquals(8,h.state().ammunition().get("a").reserve().orElseThrow().rounds());assertTrue(h.state().ammunition().get("c").reserve().isEmpty());
        assertEquals(13,h.heals.stream().mapToDouble(HealingCommand::amount).sum());assertEquals(3,h.heals.size());
        for(var state:h.beforeHeals)for(String weapon:List.of("a","b","c"))assertEquals(5,state.ammunition().get(weapon).magazine());
        assertTrue(h.state().buffs().instances().values().stream().anyMatch(b->b.definition().id().equals("chorus_d2:kill_clip")&&b.origin().weapon().equals("b")));
        assertEquals(ABILITY,h.checks.getFirst().cause().ability());assertEquals(ABILITY,h.checks.getFirst().reason());
        assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,((AbilityUse.Receipt)h.p.useAbility(h.state(),h.request()).result()).outcome());
    }
    @Test void reloadSamplesTheCurrentLoadoutAndAcceptedWorkSurvivesAbilityDeselection() throws Exception {
        var h=new Harness(true);h.cast();h.equip(equipped("replacement"));h.choose(false);h.until(200_000);
        assertEquals(1,h.magazine("a"));assertEquals(5,h.magazine("replacement"));assertEquals(5,h.magazine("b"));
        assertEquals(equipped("replacement"),h.checks.getFirst().equipment());h.choose(true);assertTrue(h.energy()<.01);
        h.until(21_000_000);assertEquals(.5,h.energy(),1e-12);h.until(42_000_001);assertEquals(1,h.energy());
        assertEquals(1,h.checks.size());
    }
    @Test void missingCalibrationFailsBeforePaymentAndTheBaseEnergyDefinitionKeepsSourceValues() throws Exception {
        var h=new Harness(false);assertThrows(IllegalArgumentException.class,()->h.p.useAbility(h.state(),h.request()));
        assertEquals(1,h.energy());assertTrue(h.checks.isEmpty());
        var definition=h.p.program().resources().stream().filter(r->r.id().equals(ENERGY)).findFirst().orElseThrow();
        assertEquals(1./42,definition.baseRate());assertTrue(definition.gainProfile().isEmpty()); // Explicit identity path: source CES is 1.
        assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
    }
    @Test void deniedDelayedReloadKeepsThePaidChargeAndTheTransferPolicySkipsAlreadyFullWeapons() throws Exception {
        var h=new Harness(true);h.cast();h.allowed=false;h.until(200_000);assertEquals(1,h.magazine("a"));assertTrue(h.energy()<.01);assertTrue(h.heals.isEmpty());
        var full=new Harness(true);full.cast();full.until(200_000);assertEquals(3,full.heals.size());
        full.until(42_000_001);full.cast();full.until(42_200_001);
        assertEquals(2,full.checks.size());assertEquals(3,full.heals.size());assertEquals(8,full.state().ammunition().get("a").reserve().orElseThrow().rounds());
    }
}
