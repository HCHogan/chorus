package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

public class IcarusCureGameTest {
    static final String COUNTER="chorus_d2:icarus_air_kill_progress", SEEN="chorus_d2:icarus_air_kill_receipts", CURE="chorus_d2:cure_cooldown";
    static ProjectileGameTest.Harness harness(GameTestHelper h) throws Exception {
        var t=FirespriteGameTest.harness(h,data->{
            RadiantGameTest.merge(data,"icarus_dash","icarus_dash_inputs","icarus_dash_cure","icarus_cure_inputs","cure");
            EmberOfSearingGameTest.addCorpseCleanup(data);IcarusDashGameTest.version(data);
        });
        t.runtime.unbind("tempering");t.runtime.unbind("firesprite");t.owner.getFoodData().setFoodLevel(0);
        // Firesprite's setup casts a synthetic healing grenade; start this scenario from an explicit baseline.
        t.owner.setHealth(10);
        IncandescentGameTest.bind(t,"icarus-input","test:icarus_cure_inputs","");select(t,true);return t;
    }
    static String id(ProjectileGameTest.Harness t){return t.owner.getUUID().toString();}
    static Optional<BuffInstance> buff(ProjectileGameTest.Harness t,String name){return FirespriteGameTest.buff(t,t.owner,name);}
    static int progress(ProjectileGameTest.Harness t){return buff(t,COUNTER).map(BuffInstance::count).orElse(0);}
    static void select(ProjectileGameTest.Harness t,boolean yes){t.runtime.abilities(new AbilityChange(id(t),FirespriteGameTest.state(t).abilities().getOrDefault(id(t),AbilityLoadout.EMPTY),yes?new AbilityLoadout(Map.of(IcarusDashGameTest.SLOT,IcarusDashGameTest.ABILITY)):AbilityLoadout.EMPTY));}
    static LivingEntity target(ProjectileGameTest.Harness t,String rank){var v=FirespriteGameTest.cow(t,0,6);v.setHealth(1);v.addTag("chorus_d2:"+rank);return v;}
    static void kill(ProjectileGameTest.Harness t,String kind,String rank){
        var v=target(t,rank);t.runtime.start(new RuleEngine.Signal("test:icarus_"+kind,new EffectEvent(id(t),v.getUUID().toString(),new BuffInstance.Origin(id(t),"input","",kind.equals("super")?"test:super":""),Set.of(),Map.of())));
        t.h.assertTrue(v.isDeadOrDying(),"synthetic credit producer did not cause actual death");v.discard();t.h.assertTrue(t.runtime.failure().isEmpty(),"kill failed: "+t.runtime.failure());
    }
    @GameCase(environment="chorus_gametest:icarus_cure_weapon",maxTicks=30)
    public void realWeaponKillsHealAfterEarlierDeathReactionLandsAttackerAndDiscardsCorpse(GameTestHelper h) throws Exception {
        var t=harness(h);try {
            IncandescentGameTest.bind(t,"cleanup","test:corpse_cleanup","");
            for(String rank:List.of("rank_and_file","elite")) {
                t.owner.setOnGround(false);var victim=target(t,rank);
                t.onCue=cue->{if(cue.cue().equals("test:remove_corpse")){t.owner.setOnGround(true);victim.removeTag("chorus_d2:"+rank);victim.discard();}};
                PugilistGameTest.draw(t,rank.equals("elite")?"secondary":"primary");PugilistGameTest.impact(t,PugilistGameTest.fire(t),victim);
                h.assertTrue(t.owner.onGround()&&victim.isRemoved(),"earlier death reaction did not change live entities");
                if(rank.equals("rank_and_file"))h.assertValueEqual(progress(t),34,"historical airborne rank-and-file kill");
            }
            h.assertValueEqual(progress(t),0,"34 plus 67 threshold consumed");h.assertTrue(buff(t,CURE).isPresent(),"shared Cure was not invoked");near(h,t.owner.getHealth(),10,"Cure should wait for its pulse");
            t.finish(4,()->{near(h,t.owner.getHealth(),16,"Cure x1 actual native health");h.assertValueEqual(t.cues.size(),2,"unknown classification despite valid historical evidence");h.assertTrue(t.runtime.failure().isEmpty(),"Cure runtime failed");});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:icarus_cure_impact",maxTicks=30)
    public void airborneEligibilityIsCapturedAtImpactAndDoesNotUseLaunchOrLaterFlags(GameTestHelper h) throws Exception {
        var t=harness(h);try {
            t.owner.setOnGround(false);var grounded=target(t,"boss");PugilistGameTest.draw(t,"primary");var first=PugilistGameTest.fire(t);t.owner.setOnGround(true);PugilistGameTest.impact(t,first,grounded);
            h.assertValueEqual(progress(t),0,"launch-time airborne flag counted a grounded impact");h.assertTrue(buff(t,CURE).isEmpty(),"grounded kill invoked Cure");
            var airborne=target(t,"boss");PugilistGameTest.draw(t,"secondary");var second=PugilistGameTest.fire(t);t.owner.setOnGround(false);PugilistGameTest.impact(t,second,airborne);t.owner.setOnGround(true);
            h.assertTrue(buff(t,CURE).isPresent(),"airborne impact did not invoke Cure after grounded launch");
            t.finish(4,()->{near(h,t.owner.getHealth(),16,"only airborne confirmed kill heals");h.assertTrue(t.cues.isEmpty(),"known movement unexpectedly unresolved");});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:icarus_cure_cooldown",maxTicks=45)
    public void realSuperKillsShareCureCooldownWithAnIndependentProducer(GameTestHelper h) throws Exception {
        var t=harness(h);try {
            IncandescentGameTest.bind(t,"external-cure","chorus_d2:cure","");
            t.runtime.start(new RuleEngine.Signal("chorus:cure_requested",new EffectEvent(id(t),id(t),new BuffInstance.Origin(id(t),"external","",""),Set.of(),Map.of("tier",new Measure(2,Unit.COUNT)))));
            long deadline=buff(t,CURE).orElseThrow().deadline();
            RampageGameTest.at(t,4,()->{near(h,t.owner.getHealth(),22,"external Cure x2");t.owner.setOnGround(false);kill(t,"super","boss");h.assertValueEqual(progress(t),0,"blocked activation kept a ready counter");h.assertValueEqual(buff(t,CURE).orElseThrow().deadline(),deadline,"blocked Cure refreshed shared cooldown");});
            RampageGameTest.at(t,23,()->{near(h,t.owner.getHealth(),22,"cooldown did not reject extra healing");t.owner.setOnGround(false);kill(t,"super","boss");});
            t.finish(27,()->near(h,t.owner.getHealth(),28,"fresh Super kill after one-second shared cooldown"));
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:icarus_cure_gap",maxTicks=180)
    public void realFiveSecondKillGapsRefreshProgressWithoutRetainingOldReceiptBuckets(GameTestHelper h) throws Exception {
        var t=harness(h);try {
            t.owner.setOnGround(false);kill(t,"super","rank_and_file");
            RampageGameTest.at(t,80,()->{t.owner.setOnGround(false);kill(t,"super","rank_and_file");h.assertValueEqual(progress(t),68,"second kill refreshed five-second gap");});
            RampageGameTest.at(t,160,()->{t.owner.setOnGround(false);kill(t,"super","rank_and_file");h.assertValueEqual(progress(t),0,"three kills four seconds apart did not reach threshold");h.assertValueEqual(buff(t,SEEN).orElseThrow().components().sets().get("deaths").size(),1,"expired receipt bucket retained old deaths");});
            t.finish(165,()->near(h,t.owner.getHealth(),16,"rolling qualifying-kill window heals after eight seconds"));
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase public void actualSelectionRemovalClearsCounterAndOtherDamageDoesNotCount(GameTestHelper h) throws Exception {
        try(var t=harness(h)) {
            t.owner.setOnGround(true);kill(t,"weapon","boss");t.owner.setOnGround(false);kill(t,"grenade","boss");
            h.assertTrue(buff(t,CURE).isEmpty()&&buff(t,COUNTER).isEmpty(),"ineligible kills counted");kill(t,"super","rank_and_file");h.assertValueEqual(progress(t),34,"qualifying kill missing");
            select(t,false);h.assertTrue(buff(t,COUNTER).isEmpty()&&buff(t,SEEN).isEmpty(),"removed ability retained its counter");select(t,true);kill(t,"super","rank_and_file");h.assertValueEqual(progress(t),34,"reselection revived old progress");
            near(h,t.owner.getHealth(),10,"ineligible kills healed");h.assertTrue(t.runtime.failure().isEmpty(),"selection lifecycle failed");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:icarus_cure_failure",maxTicks=30)
    public void unknownNativeCureKeepsAppliedHealthAndConsumedProgressWithoutReplay(GameTestHelper h) throws Exception {
        var t=harness(h);try {
            t.owner.setOnGround(false);kill(t,"super","boss");t.failAfterHealing=true;
            h.runAfterDelay(4,()->{try {
                h.assertTrue(t.runtime.failure().isPresent(),"unknown applied healing must stop the runtime");near(h,t.owner.getHealth(),13,"first pulse actual health retained");
                h.assertValueEqual(progress(t),0,"counter was restored after healing failure");h.assertTrue(buff(t,CURE).isPresent(),"Cure cooldown was rolled back");
                int operations=t.operations.size();t.runtime.prepare();near(h,t.owner.getHealth(),13,"unknown Cure replayed");h.assertValueEqual(t.operations.size(),operations,"unknown Cure retried");h.succeed();
            }finally{t.close();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
