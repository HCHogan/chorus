package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.platform.minecraft.MinecraftDamageExecutor;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

public class EmberOfMercyGameTest {
    static final String RESTORE="chorus_d2:restoration", SOLACE="chorus_d2:ember_of_solace";
    static ProjectileGameTest.Harness harness(GameTestHelper h)throws Exception{
        var t=FirespriteGameTest.harness(h,data->{
            for(String fixture:List.of("restoration_effect","restoration_test_calibration","solar_effect_duration","ember_of_mercy","ember_of_solace","mercy_inputs")){
                var fragment=ThreadedSpikeGameTest.json(fixture);
                for(var entry:fragment.entrySet())if(!entry.getKey().equals("version")){
                    if(!data.has(entry.getKey()))data.add(entry.getKey(),new JsonArray());
                    entry.getValue().getAsJsonArray().forEach(e->data.getAsJsonArray(entry.getKey()).add(e));
                }
            }
        });
        IncandescentGameTest.bind(t,"mercy","chorus_d2:ember_of_mercy","");
        IncandescentGameTest.bind(t,"mercy-input","test:mercy_inputs","");
        t.owner.getFoodData().setFoodLevel(7);return t;
    }
    static BuffInstance restoration(ProjectileGameTest.Harness t,LivingEntity target){return FirespriteGameTest.buff(t,target,RESTORE).orElseThrow();}
    static long remaining(ProjectileGameTest.Harness t,LivingEntity target){return restoration(t,target).deadline()-FirespriteGameTest.state(t).buffs().timeMicros();}
    static void restore(ProjectileGameTest.Harness t,LivingEntity target,int tier,double seconds,boolean scaled){
        String owner=FirespriteGameTest.id(t.owner);
        t.runtime.start(new RuleEngine.Signal(scaled?"test:restore":"test:restore_raw",new EffectEvent(owner,FirespriteGameTest.id(target),
                new BuffInstance.Origin(owner,"external-recovery","",""),Set.of(),Map.of("tier",new Measure(tier,Unit.COUNT),"duration",new Measure(seconds,Unit.SECOND)))));
    }
    @GameCase(environment="chorus_gametest:mercy_healing",maxTicks=90)
    public void physicalCollectionUsesCurrentSolaceAndRecoverySurvivesDamageAndFragmentRemoval(GameTestHelper h)throws Exception{
        var t=harness(h);
        try{
            FirespriteGameTest.pair(t);h.assertTrue(FirespriteGameTest.buff(t,t.owner,RESTORE).isEmpty(),"generation is not collection");
            var pickup=t.pickups.getFirst();var stranger=FirespriteGameTest.cow(t,0,0);stranger.setPos(pickup.position());pickup.tick();
            h.assertTrue(!pickup.isRemoved()&&FirespriteGameTest.buff(t,stranger,RESTORE).isEmpty(),"private pickup rewarded another entity");
            double initialHealth=t.owner.getHealth(); // The test grenade used during setup has already healed one HP.
            IncandescentGameTest.bind(t,"solace",SOLACE,"");t.owner.setPos(pickup.position());pickup.tick();
            h.assertTrue(pickup.isRemoved(),"pickup not consumed");h.assertValueEqual(remaining(t,t.owner),3_000_000L,"current collector Solace grants three seconds");
            t.runtime.unbind("mercy");t.runtime.unbind("solace");t.runtime.unbind("tempering");t.runtime.unbind("firesprite");
            near(h,FirespriteGameTest.stat(t,t.owner,"health_stat"),90,"remaining two-stack Tempering only; Mercy source removed");
            long deadline=restoration(t,t.owner).deadline();
            h.runAfterDelay(20,()->{try{
                var damage=MinecraftDamageExecutor.execute("mercy-damage",t.owner,h.getLevel().damageSources().generic(),2,false);
                near(h,damage.healthLoss(),2,"actual native damage during recovery");
                h.assertValueEqual(restoration(t,t.owner).deadline(),deadline,"damage must not interrupt Restoration");
            }catch(Throwable e){t.close();throw e;}});
            t.finish(62,()->{
                near(h,t.owner.getHealth(),initialHealth+10.5-2,"three seconds at synthetic 3.5 HP/s less two damage, no natural regeneration");
                h.assertTrue(FirespriteGameTest.buff(t,t.owner,RESTORE).isEmpty(),"restoration did not expire on real ticks");
                h.assertValueEqual(t.cues.size(),1,"collection fact emitted once");
            });
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase public void physicalPickupCapsLongRestorationWithoutDowngradingOrErasingItsHistory(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            restore(t,t.owner,2,20,false);var before=restoration(t,t.owner);FirespriteGameTest.pair(t);
            t.owner.setPos(t.pickups.getFirst().position());t.pickups.getFirst().tick();
            h.assertValueEqual(remaining(t,t.owner),15_000_000L,"extension caps even an already longer duration");
            var after=restoration(t,t.owner);h.assertValueEqual(after.tier(),2,"Mercy cannot downgrade active tier");
            h.assertValueEqual(after.longestDurationMicros(),20_000_000L,"extension must retain application history");
            h.assertValueEqual(after.origin(),before.origin(),"extension must retain recovery origin");
            restore(t,t.owner,1,2,false);h.assertValueEqual(remaining(t,t.owner),20_000_000L,"actual reapplication restores historic maximum");
        }h.succeed();
    }
    @GameCase public void restorationUsesRecipientsSolaceRatherThanTheApplyingPlayersFragment(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            var ally=FirespriteGameTest.cow(t,3,0);IncandescentGameTest.bind(t,"solace",SOLACE,"");
            restore(t,ally,1,4,true);h.assertValueEqual(remaining(t,ally),4_000_000L,"producer duration bonus must not leak to ally");
            var owner=FirespriteGameTest.id(ally);t.runtime.bind(new EffectSource("ally-solace",SOLACE,owner,new BuffInstance.Origin(owner,"ally-solace","",""),Set.of()));
            restore(t,ally,2,4,true);h.assertValueEqual(remaining(t,ally),6_000_000L,"recipient duration modifier");
            h.assertValueEqual(restoration(t,ally).tier(),2,"application upgrades tier");
        }h.succeed();
    }
}
