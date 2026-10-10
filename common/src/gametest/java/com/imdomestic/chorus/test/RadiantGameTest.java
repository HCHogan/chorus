package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

public class RadiantGameTest {
    static void merge(JsonObject data,String...fixtures){
        for(String fixture:fixtures)for(var entry:ThreadedSpikeGameTest.json(fixture).entrySet())if(!entry.getKey().equals("version")){
            if(!data.has(entry.getKey()))data.add(entry.getKey(),new JsonArray());
            entry.getValue().getAsJsonArray().forEach(e->data.getAsJsonArray(entry.getKey()).add(e));
        }
    }
    static void prepare(JsonObject data){
        merge(data,"radiant","empowering_damage","radiant_inputs","solar_effect_duration","ember_of_solace");
        data.add("weapons",ThreadedSpikeGameTest.json("radiant_weapon").get("weapons"));
    }
    static ProjectileGameTest.Harness harness(GameTestHelper h)throws Exception{return harness(h,_ -> {});}
    static ProjectileGameTest.Harness harness(GameTestHelper h,java.util.function.Consumer<JsonObject> edit)throws Exception{
        var t=FirespriteGameTest.harness(h,data->{prepare(data);edit.accept(data);});t.runtime.unbind("tempering");t.runtime.unbind("firesprite");
        IncandescentGameTest.bind(t,"radiant-input","test:radiant_inputs","");return t;
    }
    static void grant(ProjectileGameTest.Harness t,String kind,double duration){
        String owner=FirespriteGameTest.id(t.owner);t.runtime.start(new RuleEngine.Signal("test:"+kind,new EffectEvent(owner,owner,new BuffInstance.Origin(owner,"input","",""),Set.of(),Map.of("duration",new Measure(duration,Unit.SECOND)))));
        t.h.assertTrue(t.runtime.failure().isEmpty(),"Radiant input failed");
    }
    static void impact(ProjectileGameTest.Harness t,com.imdomestic.chorus.platform.minecraft.EffectProjectile shot){
        for(int i=0;i<30&&!shot.isRemoved();i++)shot.tick();
        t.h.assertTrue(shot.isRemoved()&&t.runtime.failure().isEmpty(),"physical impact failed: "+t.runtime.failure());
    }
    @GameCase public void actualProjectileObservesChampionAtImpactAndWellOverridesItsThirtyPercent(GameTestHelper h)throws Exception{
        for(boolean champion:List.of(false,true))try(var t=harness(h)){
            grant(t,"radiant",10);PugilistGameTest.draw(t,"primary");var target=FirespriteGameTest.cow(t,0,6);var shot=PugilistGameTest.fire(t);
            if(champion)target.addTag("chorus_d2:champion"); // Changed after launch; read by contact observation.
            impact(t,shot);near(h,target.getHealth(),champion?87:88,"actual per-victim damage");
            near(h,t.hits.getLast().impact().number("radiant_champion",Unit.COUNT).value(),champion?1:0,"observed champion input");target.discard();
            grant(t,"well",1);PugilistGameTest.draw(t,"secondary");var next=FirespriteGameTest.cow(t,0,6);next.addTag("chorus_d2:champion");impact(t,PugilistGameTest.fire(t));
            near(h,next.getHealth(),87.5,"Well priority chooses 25 percent instead of 30 percent or multiplying both");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:radiant_expiry",maxTicks=240)
    public void tenSecondExpiryDoesNotRewriteAnEarlierShotAndNewAttacksHaveNoExpiredBonus(GameTestHelper h)throws Exception{
        var t=harness(h);
        try{
            grant(t,"radiant",10);final LivingEntity[] target=new LivingEntity[1];
            RampageGameTest.at(t,198,()->{
                target[0]=FirespriteGameTest.cow(t,0,14);target[0].addTag("chorus_d2:champion");PugilistGameTest.draw(t,"primary");PugilistGameTest.fire(t);PugilistGameTest.draw(t,"secondary");
                t.runtime.unbind("radiant-input");h.assertTrue(FirespriteGameTest.buff(t,t.owner,"chorus_d2:radiant").isPresent(),"shot must launch before expiry");
            });
            RampageGameTest.at(t,202,()->{h.assertTrue(FirespriteGameTest.buff(t,t.owner,"chorus_d2:radiant").isEmpty(),"ten-second lifetime");near(h,target[0].getHealth(),100,"still in flight after expiry");});
            t.finish(215,()->{
                near(h,target[0].getHealth(),87,"earlier shot retains availability, late victim supplies champion classification");target[0].discard();
                try{var next=FirespriteGameTest.cow(t,0,6);next.addTag("chorus_d2:champion");impact(t,PugilistGameTest.fire(t));near(h,next.getHealth(),90,"new shot receives no expired bonus");}catch(Exception e){throw new RuntimeException(e);}
            });
        }catch(Exception|Error e){t.close();throw e;}
    }
}
