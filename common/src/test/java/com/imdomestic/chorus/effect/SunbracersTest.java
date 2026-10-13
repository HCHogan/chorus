package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SunbracersTest {
    static final String D="chorus_d2:", EXOTIC=D+"sunbracers", GRENADE=D+"solar_grenade";
    static CompiledEffects program()throws Exception{
        var parts=new ArrayList<EffectProgram>();parts.add(SolarGrenadeTest.program(true).program());
        for(String name:List.of("sunbracers","lightweight_knife","lightweight_knife_precision_test_calibration")){
            var x=json(name);if(name.equals("lightweight_knife"))json("lightweight_knife_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e->x.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value",e.getValue()));
            parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,x).getOrThrow());
        }
        return CompiledEffects.link(parts);
    }
    static EffectSource exotic(String id,String who){return new EffectSource(id,EXOTIC,who,new BuffInstance.Origin(who,id,"",""),Set.of(EXOTIC),Map.of("fastball_speed_delta",new Measure(.25,Unit.DELTA)));}
    static class Harness extends FireboltTest.Harness {
        RuleEngine.Signal lastKill;int victims;
        Harness()throws Exception{super(EffectState.Mode.PVE,program(),GRENADE);positions.keySet().removeIf(k->k.startsWith("enemy"));send(SourceChange.bind(exotic("sun","player")));}
        @Override DamageReceipt damage(RuleEngine.WorldRequest r,DamageCommand c){var receipt=super.damage(r,c);DamageFacts.from(c,receipt).stream().filter(s->s.type().equals("chorus:kill")).findFirst().ifPresent(s->lastKill=s);return receipt;}
        List<BuffInstance> fields(){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(SolarGrenadeTest.FIELD)).toList();}
        Optional<BuffInstance> ready(){return buff("sunbracers_ready","player");} Optional<BuffInstance> active(){return buff("sunbracers_active","player");}
        void knife(){
            var before=state().abilities().get("player");var slots=new HashMap<>(before.slots());slots.put(D+"melee",D+"lightweight_knife");send(new AbilityChange("player",before,new AbilityLoadout(slots)).signal());
            String target="knife-target/"+ ++victims;put(target,0);hp.put(target,1.);
            var request=new AbilityUse.Request("player",D+"melee","knife/"+victims,new EffectEvent("player",target,new BuffInstance.Origin("player","input","",""),Set.of(),Map.of()));send(request.signal());
            var impact=new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,FireboltTest.point(0),Optional.of(target),0,0,0,50_000).withGeometry(new ProjectileFlight.Geometry(new com.imdomestic.chorus.effect.target.WorldPosition("world",0,40,0),1,1,1,.125));send(launches.getLast().finish(impact));assertEquals(0,hp.get(target));
        }
    }
    @Test void paidPhysicalKnifeReceiptArmsFiveSecondsWithoutGivingAnyImmediateGrenadeEnergy()throws Exception{
        var h=new Harness();h.cast();h.knife();assertEquals(0,h.energy());assertEquals(5_000_000,h.ready().orElseThrow().deadline());assertTrue(h.active().isEmpty());
        assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,((AbilityUse.Receipt)h.p.useAbility(h.state(),h.request()).result()).outcome());h.send(h.request().signal());assertTrue(h.ready().isPresent()&&h.active().isEmpty());
        h.until(4_999_999);assertTrue(h.ready().isPresent());h.until(5_000_000);assertTrue(h.ready().isEmpty());
        assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
    }
    @Test void passiveLifetimeAndFastballAreCapturedAndDuplicateExoticsDoNotMultiplyThem()throws Exception{
        for(boolean touch:List.of(false,true)){
            var h=new Harness();if(touch)h.touch();h.send(SourceChange.bind(exotic("copy","player")));h.cast();assertEquals(25,h.launches.getFirst().parameters().speed());h.send(SourceChange.remove("sun"));h.send(SourceChange.remove("copy"));h.land();assertEquals(touch?8_250_000:6_250_000,h.fields().getFirst().deadline());h.restore();assertEquals(touch?8_250_000:6_250_000,h.fields().getFirst().deadline());
        }
        var h=new Harness();h.send(SourceChange.remove("sun"));h.cast();h.send(SourceChange.bind(exotic("sun","player")));h.land();assertEquals(20,h.launches.getFirst().parameters().speed());assertEquals(4_250_000,h.fields().getFirst().deadline());
    }
    @Test void weaponCreatedSolarEffectsCannotBorrowTheGrenadeAbilityLifetimeOrFastballModifiers()throws Exception{
        var h=new Harness();var event=new EffectEvent("player","player",new BuffInstance.Origin("player","weapon","gun",""),Set.of("chorus:weapon_damage",D+"solar",D+"solar_grenade"),Map.of());
        assertEquals(4.25,h.p.calculate(h.state(),"player",event,D+"solar_grenade_lifetime",new Measure(4,Unit.SECOND),List.of()).output().value());
        assertEquals(20,h.p.calculate(h.state(),"player",event,D+"solar_grenade_throw_speed",new Measure(20,Unit.METER_PER_SECOND),List.of()).output().value());
    }
    @Test void activationConsumesReadyAndPaysEachThrowUsingAdditionalBaseRegenerationUntilItsExactEnd()throws Exception{
        var h=new Harness();h.knife();h.cast();assertTrue(h.ready().isEmpty());assertEquals(4_000_000,h.active().orElseThrow().deadline());assertEquals(0,h.energy());
        h.until(500_000);assertEquals(100.5/199.5,h.energy(),1e-12);
        for(int i=1;i<=4;i++){h.until(i*1_000_000L);assertEquals(1,h.energy());h.cast();assertEquals(0,h.energy());}
        assertTrue(h.active().isEmpty());assertEquals(6,h.launches.size());h.until(5_000_000);assertEquals(1/199.5,h.energy(),1e-12);
    }
    @Test void onlySelectedSolarGrenadeReceivesTheBurstAndSwitchingAccountsSplitsRegeneration()throws Exception{
        var h=new Harness();h.knife();h.cast();h.until(100_000);double previous=h.energy();h.choose(D+"firebolt");h.until(200_000);assertEquals(previous+.1/83.8,h.energy(),1e-12);h.choose(GRENADE);h.until(300_000);assertEquals(previous+.1/83.8+.1*201/199.5,h.energy(),1e-12);
        var other=new Harness();other.choose(D+"firebolt");other.knife();assertTrue(other.ready().isEmpty());other.cast();assertTrue(other.active().isEmpty());assertEquals(20,other.launches.getLast().parameters().speed());
    }
    @Test void statAndBenevolenceAreAdditiveToTheUnscaledBaseAndBurstDoesNotAlterChunkGains()throws Exception{
        var h=new Harness();h.send(SourceChange.bind(new EffectSource("stats","test:benevolence_stats","player",new BuffInstance.Origin("player","stats","",""),Set.of(),Map.of("grenade",new Measure(100,Unit.STAT_POINT),"melee",new Measure(0,Unit.STAT_POINT),"class_delta",new Measure(0,Unit.DELTA)))));h.knife();h.cast();
        double baseRate=h.p.calculate(h.state(),"player",new EffectEvent("player","player",new BuffInstance.Origin("player","query","",""),Set.of(),Map.of(),Map.of(),Map.of("resource",D+"grenade_energy")),D+"grenade_regeneration",new Measure(0,Unit.CHARGE_PER_SECOND),List.of()).output().value();
        assertEquals(202.75/199.5,baseRate,1e-12);h.until(100_000);assertEquals(baseRate*.1,h.energy(),1e-12);h.bind(D+"ember_of_benevolence");h.bind("test:benevolence_inputs");h.send(new RuleEngine.Signal("test:cure_bestow",new EffectEvent("player","ally",BenevolenceTest.input("player",false).origin(),Set.of(),Map.of())));h.until(200_000);assertTrue(h.buff("benevolence_regeneration","player").isPresent());double before=h.energy();h.until(300_000);assertEquals(before+.1*(1+1.10898698+.00639461*90+200+3)/199.5,h.energy(),1e-12);double chunk=BleakWatcherAspectTest.gain(h.p,h.state(),"player",D+"grenade_energy",.04).grant().scaled();h.send(SourceChange.remove("sun"));assertEquals(chunk,BleakWatcherAspectTest.gain(h.p,h.state(),"player",D+"grenade_energy",.04).grant().scaled(),1e-12);
    }
    @Test void duplicateDeathCannotRearmButANewPoweredKillCanRearmDuringTheActiveWindow()throws Exception{
        var h=new Harness();h.send(SourceChange.bind(exotic("copy","player")));h.knife();var first=h.lastKill;h.cast();h.until(1_000_000);h.send(first);assertTrue(h.ready().isEmpty());assertEquals(4_000_000,h.active().orElseThrow().deadline());h.knife();assertTrue(h.ready().isPresent());h.cast();assertEquals(5_000_000,h.active().orElseThrow().deadline());
    }
    @Test void unpoweredAndForeignKillFactsCannotArmEvenWhenTheirDamageWasLethal()throws Exception{
        var h=new Harness();h.knife();var event=(EffectEvent)h.lastKill.payload();h.cast();
        var refs=new HashMap<>(event.references());refs.put("death_id","new-unpowered-death");var plain=new BuffInstance.Origin("player","native-melee","","");h.send(new RuleEngine.Signal("chorus:kill",new EffectEvent(event.actor(),event.victim(),plain,event.tags(),event.numbers(),event.flags(),refs)));assertTrue(h.ready().isEmpty());
        h.send(new RuleEngine.Signal("chorus:kill",new EffectEvent("other",event.victim(),new BuffInstance.Origin("other","foreign","",D+"lightweight_knife",Set.of("chorus:melee_ability")),event.tags(),event.numbers(),event.flags(),refs)));assertTrue(h.ready().isEmpty());
    }
    @Test void removingTheLastExoticStopsRegenerationButRetainsPreviouslyAcceptedPhysicalField()throws Exception{
        var h=new Harness();h.send(SourceChange.bind(exotic("copy","player")));h.knife();h.cast();h.impact(1,ProjectileFlight.End.BLOCK);h.until(500_000);h.send(SourceChange.remove("sun"));assertTrue(h.active().isPresent());h.restore();h.send(SourceChange.remove("copy"));assertTrue(h.active().isEmpty()&&h.ready().isEmpty());double energy=h.energy();h.until(1_000_000);assertEquals(energy+.5/199.5,h.energy(),1e-12);assertEquals(6_250_000,h.fields().getFirst().deadline());
    }
    @Test void heatRisesConsumesTheSelectedSolarChargeAndStartsTheBurstWithoutThrowing()throws Exception{
        var h=new Harness();h.knife();h.send(SourceChange.bind(HeatRisesTest.aspect("heat",60)));int count=h.launches.size();var request=new AbilityUse.Request("player",D+"grenade","heat/1",new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of("chorus:ability_input_release"),Map.of("input_hold_time",new Measure(.3,Unit.SECOND))));h.send(request.signal());assertEquals(count,h.launches.size());assertTrue(h.active().isPresent()&&h.ready().isEmpty());assertTrue(h.buff("heat_rises","player").isPresent());assertEquals(0,h.energy());
    }
    @Test void unknownKillDoesNotArmAndUnknownPaidLaunchCannotRefundReplayOrRollBackTheActivatedBurst()throws Exception{
        var failed=new Harness();failed.failDamage=true;assertThrows(IllegalStateException.class,failed::knife);assertTrue(failed.ready().isEmpty());assertEquals(1,failed.hits.size());
        var h=new Harness();h.knife();h.failLaunch=true;assertThrows(IllegalStateException.class,h::cast);assertEquals(0,h.energy());assertTrue(h.active().isPresent()&&h.ready().isEmpty());int before=h.launches.size();assertThrows(IllegalStateException.class,()->h.until(1_000_000));assertEquals(before,h.launches.size());
    }
}
