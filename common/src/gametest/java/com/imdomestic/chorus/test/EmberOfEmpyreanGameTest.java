package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

public class EmberOfEmpyreanGameTest {
    static final String RAD="chorus_d2:radiant", REST="chorus_d2:restoration";
    static ProjectileGameTest.Harness harness(GameTestHelper h)throws Exception{
        var t=RadiantGameTest.harness(h,data->RadiantGameTest.merge(data,"ember_of_empyrean","empyrean_inputs","restoration_effect","restoration_test_calibration","mercy_inputs","ember_of_mercy","solar","solar_test_calibration","solar_test_source"));
        IncandescentGameTest.bind(t,"empyrean","chorus_d2:ember_of_empyrean","");IncandescentGameTest.bind(t,"empyrean-input","test:empyrean_inputs","");IncandescentGameTest.bind(t,"mercy-input","test:mercy_inputs","");return t;
    }
    static BuffInstance active(ProjectileGameTest.Harness t,String buff){return FirespriteGameTest.buff(t,t.owner,buff).orElseThrow();}
    static long remaining(ProjectileGameTest.Harness t,String buff){return active(t,buff).deadline()-FirespriteGameTest.state(t).buffs().timeMicros();}
    static void kill(ProjectileGameTest.Harness t,String slot,String tier)throws Exception{
        var target=FirespriteGameTest.cow(t,0,6);target.setHealth(1);target.addTag(tier==null?"chorus_d2:boss":"chorus_d2:combatant_tier_"+tier);
        PugilistGameTest.draw(t,slot);PugilistGameTest.impact(t,PugilistGameTest.fire(t),target);
    }
    @GameCase public void solarWeaponKillsExtendBothEffectsAndSubsequentMercyPickupOnlyExtendsRestoration(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            IncandescentGameTest.bind(t,"tempering","chorus_d2:ember_of_tempering","");IncandescentGameTest.bind(t,"firesprite","chorus_d2:firesprite_system","");IncandescentGameTest.bind(t,"mercy","chorus_d2:ember_of_mercy","");
            RadiantGameTest.grant(t,"radiant",10);EmberOfMercyGameTest.restore(t,t.owner,2,2,false);
            kill(t,"primary","1");h.assertValueEqual(remaining(t,RAD),11_500_000L,"tier-one Radiant extension");h.assertValueEqual(remaining(t,REST),3_500_000L,"tier-one Restoration extension");
            kill(t,"secondary","4");h.assertValueEqual(remaining(t,RAD),15_000_000L,"Radiant capped after tier four");h.assertValueEqual(remaining(t,REST),9_500_000L,"Restoration extended independently");
            h.assertValueEqual(t.pickups.size(),1,"Tempering produced one private Firesprite");IncandescentGameTest.bind(t,"solace","chorus_d2:ember_of_solace","");
            t.owner.setPos(t.pickups.getFirst().position());t.pickups.getFirst().tick();
            h.assertValueEqual(remaining(t,REST),12_500_000L,"Mercy adds current Solace three seconds");h.assertValueEqual(remaining(t,RAD),15_000_000L,"Mercy did not extend Radiant");h.assertValueEqual(active(t,REST).tier(),2,"both extenders preserve Restoration tier");
            near(h,FirespriteGameTest.stat(t,t.owner,"health_stat"),90,"base 50 plus Tempering 40 and Mercy 10 minus Empyrean 10");
            t.runtime.unbind("empyrean");near(h,FirespriteGameTest.stat(t,t.owner,"health_stat"),100,"current fragment penalty removed");
        }h.succeed();
    }
    @GameCase public void longTimersAreCappedButUnknownRanksLeaveThemUnchangedAndReportClassificationGap(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            RadiantGameTest.grant(t,"radiant",25);EmberOfMercyGameTest.restore(t,t.owner,2,20,false);var origin=active(t,REST).origin();
            kill(t,"primary",null);h.assertValueEqual(remaining(t,RAD),25_000_000L,"unknown tier must not guess an extension or cap");h.assertValueEqual(t.cues.getLast().cue(),"test:empyrean_unclassified","explicit unknown classification");
            kill(t,"secondary","2");h.assertValueEqual(remaining(t,RAD),15_000_000L,"long Radiant reduced to cap");h.assertValueEqual(remaining(t,REST),15_000_000L,"long Restoration reduced to cap");
            h.assertValueEqual(active(t,RAD).longestDurationMicros(),25_000_000L,"Radiant history retained");h.assertValueEqual(active(t,REST).longestDurationMicros(),20_000_000L,"Restoration history retained");h.assertValueEqual(active(t,REST).origin(),origin,"restoration provenance retained");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:empyrean_dot",maxTicks=40)
    public void actualScorchPeriodicDeathExtendsBothStatesForItsAttributedKiller(GameTestHelper h)throws Exception{
        var t=harness(h);
        try{
            RadiantGameTest.grant(t,"radiant",10);EmberOfMercyGameTest.restore(t,t.owner,2,4,false);long radiant=active(t,RAD).deadline(),restoration=active(t,REST).deadline();
            var victim=FirespriteGameTest.cow(t,0,6);victim.setHealth(.1f);victim.addTag("chorus_d2:combatant_tier_2");EmberOfSearingGameTest.scorch(t,t.owner,victim);
            t.finish(11,()->{h.assertTrue(victim.isDeadOrDying(),"Scorch did not actually kill");h.assertValueEqual(active(t,RAD).deadline(),radiant+2_250_000L,"Solar status death extends Radiant");h.assertValueEqual(active(t,REST).deadline(),restoration+2_250_000L,"Solar status death extends Restoration");h.assertTrue(t.cues.isEmpty(),"known tier unexpectedly failed classification");});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
