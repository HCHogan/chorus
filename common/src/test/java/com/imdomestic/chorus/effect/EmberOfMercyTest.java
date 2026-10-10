package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Mercy's confirmed pickup leg. Revival is deliberately not modeled by a synthetic death/respawn. */
class EmberOfMercyTest {
    static final String RESTORE="chorus_d2:restoration", MERCY="chorus_d2:ember_of_mercy", SOLACE="chorus_d2:ember_of_solace";
    static CompiledEffects program() throws Exception {
        return link("firesprite","firesprite_test_calibration","ember_of_tempering","character_stats","weapon_stats","arcbolt_energy","tempering_weapon",
                "restoration_effect","restoration_test_calibration","solar_effect_duration","ember_of_mercy","ember_of_solace","mercy_inputs");
    }
    static class Harness extends FirespriteTest.Harness {
        Harness() throws Exception { super(program());bind("player",MERCY);bind("player","test:mercy_inputs"); }
        BuffInstance restoration(String holder){return buff(holder,RESTORE).orElseThrow();}
        long remaining(String holder){return restoration(holder).deadline()-now();}
        void restore(String target,int tier,double seconds,boolean scaled){
            session.start(now(),new RuleEngine.Signal(scaled?"test:restore":"test:restore_raw",new EffectEvent("player",target,
                    source("player","test:mercy_inputs").origin(),Set.of(),Map.of("tier",new Measure(tier,Unit.COUNT),"duration",new Measure(seconds,Unit.SECOND)))));healthy();
        }
        void collected(String holder,String kind){
            // The fact belongs to the collector even if the pickup originated from someone else.
            session.start(now(),new WorldPickup.Contact(WorldPickup.End.COLLECTED,FirespriteTest.POINT,Optional.of(holder),1)
                    .fact("unit/"+now(),kind,new BuffInstance.Origin("producer","pickup","","")));healthy();
        }
        double healed(String holder){return heals.stream().filter(h->h.target().equals(holder)).mapToDouble(h->h.amount()).sum();}
    }
    @Test void physicalContinuationFactGrantsTwoSecondsAndHighestTierHistorySurviveExtensionAndCap() throws Exception {
        var h=new Harness();h.select("player",null);h.spawn("player");h.finish(0,1,true);
        assertEquals(2_000_000,h.remaining("player"));assertEquals(1,h.restoration("player").tier());assertEquals(0,h.energy("player"));
        h.restore("player",2,20,false);var origin=h.restoration("player").origin();
        h.collected("player","chorus_d2:firesprite");
        assertEquals(15_000_000,h.remaining("player"));assertEquals(20_000_000,h.restoration("player").longestDurationMicros());
        assertEquals(2,h.restoration("player").tier());assertEquals(origin,h.restoration("player").origin());
        h.restore("player",1,2,false);assertEquals(20_000_000,h.remaining("player"));assertEquals(2,h.restoration("player").tier());
        h.until(1_000_000);assertEquals(5,h.healed("player"),1e-12);
    }
    @Test void solaceIsEvaluatedOnCollectorAtCollectionAndUnequipDoesNotRewriteExistingDuration() throws Exception {
        var h=new Harness();h.spawn("player");h.bind("player",SOLACE);h.finish(0,1,true);
        assertEquals(3_000_000,h.remaining("player"));h.remove("player",SOLACE);assertEquals(3_000_000,h.remaining("player"));
        h.until(1_000_000);h.collected("player","chorus_d2:firesprite");assertEquals(4_000_000,h.remaining("player"));
        h.bind("ally",SOLACE);h.collected("player","chorus_d2:firesprite");assertEquals(6_000_000,h.remaining("player"));
        h.bind("player",SOLACE);h.collected("player","chorus_d2:firesprite");assertEquals(9_000_000,h.remaining("player"));
        h.remove("player",MERCY);h.collected("player","chorus_d2:firesprite");assertEquals(9_000_000,h.remaining("player"));
        h.until(10_000_000);assertTrue(h.buff("player",RESTORE).isEmpty());assertEquals(35,h.healed("player"),1e-9);
    }
    @Test void sourceSolaceDoesNotLengthenAnotherRecipientsRestorationAndRecipientSolaceDoes() throws Exception {
        var h=new Harness();h.bind("player",SOLACE);h.restore("ally",1,4,true);
        assertEquals(4_000_000,h.remaining("ally"));h.bind("ally",SOLACE);h.restore("ally",2,4,true);
        assertEquals(6_000_000,h.remaining("ally"));assertEquals(2,h.restoration("ally").tier());
        h.remove("ally",SOLACE);h.restore("ally",1,2,true);assertEquals(6_000_000,h.remaining("ally"),"historic maximum remains independent of a later shorter application");
        assertEquals("player",h.restoration("ally").origin().owner());
    }
    @Test void pickupKindCollectorSelectionExpiryAndUnbindingAreIndependentOfGrenadeEnergy() throws Exception {
        var h=new Harness();h.collected("player","chorus_d2:orb_of_power");h.collected("ally","chorus_d2:firesprite");
        assertTrue(h.buff("player",RESTORE).isEmpty());assertTrue(h.buff("ally",RESTORE).isEmpty());
        h.spawn("player");h.remove("player",MERCY);h.finish(0,1,true);
        assertTrue(h.buff("player",RESTORE).isEmpty());assertEquals(.0375,h.energy("player"),1e-12);
        h.bind("player",MERCY);h.until(5_000_000);h.spawn("player");h.until(30_000_000);h.finish(1,25_000_000,false);
        assertTrue(h.buff("player",RESTORE).isEmpty());
    }
    @Test void exactExpiryCreatesFreshTierOneAndExtensionsAreNotHistoricReapplications() throws Exception {
        var h=new Harness();h.restore("player",2,4,false);h.until(3_000_000);
        h.collected("player","chorus_d2:firesprite");assertEquals(3_000_000,h.remaining("player"));assertEquals(4_000_000,h.restoration("player").longestDurationMicros());
        h.until(6_000_000);h.collected("player","chorus_d2:firesprite");assertEquals(2_000_000,h.remaining("player"));assertEquals(1,h.restoration("player").tier());
    }
    @Test void healthBonusAndDurationProfileRoundTripAndRecoveryCalibrationIsRequired() throws Exception {
        var h=new Harness();assertEquals(60,h.statQuery("player","health_stat",50));assertEquals(200,h.statQuery("player","health_stat",195));
        h.remove("player",MERCY);assertEquals(50,h.statQuery("player","health_stat",50));
        var encoded=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.program.program()).getOrThrow();
        assertEquals(h.program.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,encoded).getOrThrow());
        var data=encoded.deepCopy().getAsJsonObject();var profiles=data.getAsJsonArray("profiles");
        for(int i=profiles.size()-1;i>=0;i--)if(profiles.get(i).getAsJsonObject().get("id").getAsString().equals("chorus_d2:restoration_rate"))profiles.remove(i);
        assertThrows(RuntimeException.class,()->compile(data));
    }
}
