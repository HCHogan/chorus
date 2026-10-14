package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class IncineratorSnapTest {
    static final String D="chorus_d2:",ABILITY=D+"incinerator_snap";
    static CompiledEffects program(boolean calibrated)throws Exception {
        var data=json("incinerator_snap");if(calibrated)json("incinerator_snap_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e->data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value",e.getValue()));
        return CompiledEffects.link(List.of(SunbracersTest.program().program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("incinerator_snap_test_calibration").get("program")).getOrThrow()));
    }
    static class Harness extends FireboltTest.Harness {
        Harness()throws Exception{this(EffectState.Mode.PVE);}
        Harness(EffectState.Mode mode)throws Exception{super(mode,program(true),D+"solar_grenade");positions.keySet().removeIf(k->k.startsWith("enemy")||k.equals("ally"));put("enemy",0);select();}
        void select(){var before=state().abilities().get("player");var slots=new HashMap<>(before.slots());slots.put(D+"melee",ABILITY);send(new AbilityChange("player",before,new AbilityLoadout(slots)).signal());}
        AbilityUse.Request meleeRequest(){return new AbilityUse.Request("player",D+"melee","snap/"+ ++casts,new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of()));}
        void snap(){var r=meleeRequest();assertEquals(AbilityUse.Outcome.ACCEPTED,((AbilityUse.Receipt)p.useAbility(state(),r).result()).outcome());send(r.signal());}
        void burst(int index,ProjectileFlight.End end){send(launches.get(index).finish(new ProjectileFlight.Impact(end,FireboltTest.point(0),end.hasTarget()?Optional.of("enemy"):Optional.empty(),0,end==ProjectileFlight.End.BLOCK?1:0,0,50_000)));}
        double uses(){return state().resources().get(new ResourceState.Key("player",D+"solar_melee_uses")).value();}
        double scorch(){return buff("scorch","enemy").map(BuffInstance::quantity).orElse(0.);}
        List<DamageCommand> sparks(){return hits.stream().filter(x->x.tags().contains(D+"incinerator_snap_damage")).toList();}
        void exotic(){send(SourceChange.bind(SunbracersTest.exotic("sun","player")));}
    }
    @Test void requiredCalibrationPrecedesOnePaymentAndFiveIndependentFlights()throws Exception {
        var h=new Harness();assertEquals(1,h.uses());assertThrows(IllegalArgumentException.class,()->program(false).useAbility(h.state(),h.meleeRequest()));assertEquals(1,h.uses());
        h.snap();assertEquals(0,h.uses());assertEquals(5,h.launches.size());assertEquals(5,new HashSet<>(h.launches.stream().map(x->x.direction().orElseThrow()).toList()).size());
        assertTrue(h.launches.stream().allMatch(x->x.parameters().range().orElseThrow()==8&&x.parameters().proximity().orElseThrow().armDistance()==.5));
        assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,((AbilityUse.Receipt)h.p.useAbility(h.state(),h.meleeRequest()).result()).outcome());
        var progress=h.state().resources().get(new ResourceState.Key("player",D+"solar_melee_progress"));assertEquals(1/119.8,h.p.resourceRate(h.state(),progress).perSecond(),1e-12);
        assertEquals(.45,BleakWatcherAspectTest.gain(h.p,h.state(),"player",D+"solar_melee_progress",.5).grant().scaled(),1e-12);
        // Decimal storage of 1 / 119.8 and a ceiling to integer microseconds can add one microsecond.
        h.until(119_799_999);assertEquals(0,h.uses());h.until(119_800_001);assertEquals(1,h.uses());
        assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
    }
    @Test void explosionsUseActualRecipientKindAndSourceSpecificAshesStacks()throws Exception {
        for(var mode:EffectState.Mode.values())for(boolean guardian:List.of(false,true))for(boolean ashes:List.of(false,true)) {
            var h=new Harness(mode);if(guardian)h.players.add("enemy");if(ashes)h.bind(D+"ember_of_ashes");h.snap();h.burst(0,ProjectileFlight.End.PROXIMITY);
            assertEquals(guardian?2.7:9,h.amounts.getFirst(),1e-9);assertEquals(guardian?(ashes?15:10):(ashes?30:20),h.scorch());assertEquals(ABILITY,h.hits.getFirst().source().ability());
            assertEquals(Set.of("chorus:ability_kill","chorus:melee_kill"),h.hits.getFirst().killTags());
        }
    }
    @Test void allFourDetonationReasonsWorkWhileExpiryAndUnavailableDoNotExplode()throws Exception {
        for(var end:List.of(ProjectileFlight.End.ENTITY,ProjectileFlight.End.BLOCK,ProjectileFlight.End.RANGE,ProjectileFlight.End.PROXIMITY,ProjectileFlight.End.EXPIRED,ProjectileFlight.End.UNLOADED)) {
            var h=new Harness();h.snap();h.burst(0,end);boolean detonate=end!=ProjectileFlight.End.EXPIRED&&end!=ProjectileFlight.End.UNLOADED;assertEquals(detonate?1:0,h.sparks().size());assertEquals(detonate?20:0,h.scorch());
        }
    }
    @Test void fiveBlastsReachSharedIgnitionThresholdAndPreserveMeleeCredit()throws Exception {
        var h=new Harness();h.snap();for(int i=0;i<5;i++)h.burst(i,ProjectileFlight.End.PROXIMITY);
        assertEquals(5,h.sparks().size());assertEquals(45,h.sparks().stream().mapToDouble(x->h.amounts.get(h.hits.indexOf(x))).sum(),1e-9);assertEquals(0,h.scorch());
        h.until(500_000);var ignition=h.hits.stream().filter(x->x.tags().contains(D+"ignition_damage")).findFirst().orElseThrow();
        assertEquals(ABILITY,ignition.source().ability());assertTrue(ignition.killTags().contains("chorus:melee_kill"));assertTrue(ignition.source().tags().contains("chorus:melee_ability"));
    }
    @Test void immuneZeroLossDeniedAndLethalBlastsCannotInventScorch()throws Exception {
        for(String kind:List.of("immune","zero","denied","lethal")) {
            var h=new Harness();h.snap();switch(kind){case "immune"->h.damageOutcome=DamageReceipt.Outcome.IMMUNE;case "zero"->h.noLoss=true;case "denied"->h.denied.add("enemy");default->h.hp.put("enemy",1.);}
            h.burst(0,ProjectileFlight.End.RANGE);assertEquals(0,h.scorch());assertEquals(kind.equals("denied")?1:0,h.checks.size());
        }
    }
    @Test void directAndScorchKillsArmSunbracersAndActualGrenadePaymentConsumesReady()throws Exception {
        for(boolean dot:List.of(false,true)) {
            var h=new Harness();h.exotic();h.hp.put("enemy",dot?9.01:1.);h.snap();h.burst(0,ProjectileFlight.End.PROXIMITY);
            if(dot){assertTrue(h.buff("sunbracers_ready","player").isEmpty());h.until(500_000);assertTrue(h.hits.stream().anyMatch(x->x.tags().contains(D+"scorch_damage")));}
            assertEquals(0,h.hp.get("enemy"));assertTrue(h.buff("sunbracers_ready","player").isPresent());h.cast();assertTrue(h.buff("sunbracers_ready","player").isEmpty());assertTrue(h.buff("sunbracers_active","player").isPresent());assertEquals(0,h.energy());
        }
    }
    @Test void acceptedSparksRetainParametersAndSnapshotsAfterMeleeSelectionIsRemoved()throws Exception {
        var h=new Harness();h.bind(D+"ember_of_ashes");h.snap();var before=h.state().abilities().get("player");var slots=new HashMap<>(before.slots());slots.remove(D+"melee");h.send(new AbilityChange("player",before,new AbilityLoadout(slots)).signal());h.send(SourceChange.remove(D+"ember_of_ashes"));
        for(var launch:h.launches)assertEquals(launch,com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec.DATA.decode(com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec.DATA.encode(launch),ProjectileFlight.Launch.class));
        h.burst(0,ProjectileFlight.End.RANGE);assertEquals(30,h.scorch());assertEquals(9,h.amounts.getFirst(),1e-9);assertEquals(ABILITY,h.sparks().getFirst().source().ability());
    }
    @Test void unknownLaunchKeepsTheCostAndCannotReplayOrLaunchTheRemainingFan()throws Exception {
        var h=new Harness();h.failLaunch=true;assertThrows(IllegalStateException.class,h::snap);assertEquals(0,h.uses());assertEquals(1,h.launches.size());assertThrows(IllegalStateException.class,()->h.until(1_000_000));assertEquals(1,h.launches.size());
    }
    @Test void unknownNativeDamageCannotGrantStatusOrSunbracersFromAnUnconfirmedReceipt()throws Exception {
        var h=new Harness();h.exotic();h.hp.put("enemy",1.);h.snap();h.failDamage=true;assertThrows(IllegalStateException.class,()->h.burst(0,ProjectileFlight.End.PROXIMITY));assertEquals(0,h.hp.get("enemy"));assertEquals(0,h.scorch());assertTrue(h.buff("sunbracers_ready","player").isEmpty());
    }
    @Test void directMeleeBonusDoesNotScaleTheDerivedScorchOrIgnitionDamage()throws Exception {
        for(boolean ignition:List.of(false,true)) {
            var damage=new ArrayList<Double>();
            for(boolean boost:List.of(false,true)) {
                var h=new Harness();if(boost)h.bind("test:snap_melee_boost");h.snap();for(int i=0;i<(ignition?5:1);i++)h.burst(i,ProjectileFlight.End.PROXIMITY);
                assertEquals(boost?18:9,h.amounts.getFirst(),1e-9);h.until(500_000);
                String tag=D+(ignition?"ignition_damage":"scorch_damage");var derived=h.hits.stream().filter(x->x.tags().contains(tag)).findFirst().orElseThrow();damage.add(h.amounts.get(h.hits.indexOf(derived)));
                assertEquals(ABILITY,derived.source().ability());assertTrue(derived.killTags().contains("chorus:melee_kill"));
            }
            assertTrue(damage.getFirst()>0);assertEquals(damage.getFirst(),damage.getLast(),1e-12);
        }
    }
}
