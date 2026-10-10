package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Fragment points feed existing community-fit curves; skill-specific CES remains independent. */
class SolarFragmentStatsTest {
    static final String MELEE="chorus_d2:threaded_spike_energy",GRENADE="chorus_d2:grenade_energy";
    static CompiledEffects program()throws Exception{return link("threaded_spike","combat_damage", "strand_defense","continuity","threaded_spike_energy","grenade_energy", "arcbolt_energy","character_stats","solar","solar_test_calibration","ember_of_char","ember_of_eruption","solar_attribute_inputs");}
    static double chunk(double stat){return 1.625-.625*StrictMath.cos(StrictMath.PI*Math.clamp(stat,0,100)/100);}
    static double passive(double stat){stat=Math.clamp(stat,0,100);return stat>=100?2.75:stat>=70?2.10898698+.00639461*stat:1+.004273626*stat+.000300195*stat*stat-6.37618e-7*stat*stat*stat;}
    static EffectSource source(String instance,String bundle,String owner){return new EffectSource(instance,bundle,owner,new BuffInstance.Origin(owner,instance,"",""),Set.of());}
    static class Harness {
        final CompiledEffects p;final EffectSession session;
        Harness()throws Exception {
            p=program();var s=EffectState.empty();
            for(String owner:List.of("owner","recipient")){
                s=s.withSource(source(owner+"-inputs","test:solar_attribute_inputs",owner));
                for(String resource:List.of(MELEE,GRENADE))s=s.withResource(new ResourceState(new ResourceState.Key(owner,resource),0,1,0));
            }
            session=new EffectSession(engine(p),s,_ ->{throw new AssertionError("attribute/energy must not touch the world");});
            for(String owner:List.of("owner","recipient"))session.start(0,new AbilityChange(owner,AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("chorus_d2:melee","chorus_d2:threaded_spike","chorus_d2:grenade","test:attribute_grenade"))).signal());
            stat("owner",50);stat("recipient",50);
        }
        EffectState state(){return session.state().engine().domain();}
        long now(){return state().buffs().timeMicros();}
        void stat(String owner,double value){var input=source(owner+"-inputs","test:solar_attribute_inputs",owner);session.start(now(),new RuleEngine.Signal("test:stat",new EffectEvent(owner,owner,input.origin(),Set.of(),Map.of("grenade",new Measure(value,Unit.STAT_POINT),"melee",new Measure(value,Unit.STAT_POINT)))));}
        void bind(String owner,String name,String instance){session.start(now(),SourceChange.bind(source(instance,"chorus_d2:ember_of_"+name,owner)));}
        void remove(String instance){session.start(now(),SourceChange.remove(instance));}
        double raw(String owner,String stat){return state().buffs().instances().values().stream().filter(b->b.key().holder().equals(owner)&&b.definition().id().equals("chorus_d2:"+stat+"_stat")).findFirst().orElseThrow().components().numbers().get("points");}
        double points(String owner,String stat){return p.attribute(state(),owner,"chorus_d2:"+stat+"_stat",new Measure(0,Unit.STAT_POINT),NumericQuery.Path.empty()).output().value();}
        EnergyActions.Result gain(String owner,String resource,EnergyGains.Basis basis){
            var s=source("grant","test:solar_attribute_inputs","owner");var event=new EffectEvent("owner",owner,s.origin(),Set.of(),Map.of());
            var resources=p.program().resources().stream().collect(java.util.stream.Collectors.toMap(ResourceDefinition::id,r->r));
            var e=new Evaluation(state(),new RuleEngine.Context(new RuleEngine.Event(1,1,Optional.empty(),now(),new RuleEngine.Signal("test:gain",event)),"gain",s,Map.of()),Map.of(),Map.of(),resources,Map.of(),Optional.of(p));
            return (EnergyActions.Result)((RuleEngine.Local<EffectState>)new EnergyActions.Grant(resource,Evaluation.Target.VICTIM,new Value.Constant(.04,Unit.CHARGE),basis,Map.of(),Set.of(),Map.of()).execute(e)).result();
        }
    }
    @Test void distinctFragmentStatsDoNotMixRecipientsAndDuplicateBindingsDoNotDoublePoints()throws Exception {
        var h=new Harness();h.bind("owner","char","char");h.bind("owner","char","char-copy");h.bind("recipient","eruption","eruption");
        assertEquals(60,h.points("owner","grenade"));assertEquals(50,h.points("owner","melee"));assertEquals(50,h.points("recipient","grenade"));assertEquals(60,h.points("recipient","melee"));
        h.remove("char");assertEquals(60,h.points("owner","grenade"));h.remove("char-copy");assertEquals(50,h.points("owner","grenade"));
        h.bind("recipient","eruption","eruption-copy");assertEquals(60,h.points("recipient","melee"));h.remove("eruption");h.remove("eruption-copy");assertEquals(50,h.points("recipient","melee"));
        assertEquals(50,h.raw("owner","grenade"));assertEquals(50,h.raw("recipient","melee"));
    }
    @Test void actualRecipientPointsEnterChunkCurvesBeforeIndependentCesAndFixedGainsStayFixed()throws Exception {
        var h=new Harness();h.bind("owner","char","char");h.bind("owner","eruption","eruption");
        assertEquals(.04*.75*chunk(60),h.gain("owner",GRENADE,EnergyGains.Basis.BASE).grant().scaled(),1e-12);
        assertEquals(.04*.8*chunk(60),h.gain("owner",MELEE,EnergyGains.Basis.BASE).grant().scaled(),1e-12);
        assertEquals(.04*.75*chunk(50),h.gain("recipient",GRENADE,EnergyGains.Basis.BASE).grant().scaled(),1e-12);
        assertEquals(.04*.8*chunk(50),h.gain("recipient",MELEE,EnergyGains.Basis.BASE).grant().scaled(),1e-12);
        for(String resource:List.of(MELEE,GRENADE))assertEquals(.04,h.gain("owner",resource,EnergyGains.Basis.FIXED).grant().scaled());
        h.remove("char");h.remove("eruption");assertEquals(.04*.8*chunk(50),h.gain("owner",MELEE,EnergyGains.Basis.BASE).grant().scaled(),1e-12);
    }
    @Test void equipmentChangesSplitPassiveIntegrationAndNeverRewriteStoredBasePoints()throws Exception {
        var h=new Harness();h.session.observe(500_000,List.of());h.bind("owner","char","char");h.bind("owner","eruption","eruption");
        h.session.observe(1_500_000,List.of());h.remove("char");h.remove("eruption");h.session.observe(2_000_000,List.of());
        assertEquals((passive(50)+passive(60))/151.5,h.state().resources().get(new ResourceState.Key("owner",GRENADE)).value(),1e-12);
        assertEquals((passive(50)+passive(60))/145.2,h.state().resources().get(new ResourceState.Key("owner",MELEE)).value(),1e-12);
        assertEquals(2*passive(50)/145.2,h.state().resources().get(new ResourceState.Key("recipient",MELEE)).value(),1e-12);
        assertEquals(50,h.raw("owner","grenade"));assertEquals(50,h.raw("owner","melee"));
    }
    @Test void zeroHundredAndTwoHundredBoundariesApplyBonusesBeforeStatAndCurveCaps()throws Exception {
        var h=new Harness();h.bind("owner","char","char");h.bind("owner","eruption","eruption");
        for(double base:List.of(0.,90.,100.,195.,210.)){
            h.stat("owner",base);double points=Math.clamp(base+10,0,200);
            assertEquals(points,h.points("owner","grenade"));assertEquals(points,h.points("owner","melee"));
            assertEquals(.04*.75*chunk(points),h.gain("owner",GRENADE,EnergyGains.Basis.BASE).grant().scaled(),1e-12);
            assertEquals(.04*.8*chunk(points),h.gain("owner",MELEE,EnergyGains.Basis.BASE).grant().scaled(),1e-12);
            assertEquals(base,h.raw("owner","melee"));
        }
    }
}
