package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Actual ability payment, flight, damage and shared Slow/Freeze; flight calibration is synthetic. */
public class WitheringBladeGameTest {
    static final String ABILITY="chorus_d2:withering_blade",SLOT="chorus_d2:melee",ENERGY="chorus_d2:withering_blade_energy";
    static void prepare(JsonObject data){
        var params=data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters");ThreadedSpikeGameTest.json("withering_blade_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e->params.getAsJsonObject(e.getKey()).add("value",e.getValue()));
        for(String name:List.of("withering_blade_energy","withering_blade_damage_test_calibration","slow","stasis_duration","durance","character_stats","freeze","freeze_test_falloff","combat_damage","movement_attributes","weapon_stats")){
            var part=ThreadedSpikeGameTest.json(name);for(var e:part.entrySet())if(!e.getKey().equals("version")){
                if(e.getValue().isJsonArray()){if(!data.has(e.getKey()))data.add(e.getKey(),new JsonArray());e.getValue().getAsJsonArray().forEach(v->data.getAsJsonArray(e.getKey()).add(v));}else data.add(e.getKey(),e.getValue());
            }
        }
        for(String id:List.of("test:projectile","test:power")){var b=new JsonObject();b.addProperty("id",id);data.getAsJsonArray("bundles").add(b);}
    }
    static String owner(ProjectileGameTest.Harness t){return t.owner.getUUID().toString();}
    static EffectState state(ProjectileGameTest.Harness t){return t.runtime.state().engine().domain();}
    static double energy(ProjectileGameTest.Harness t){return state(t).resources().get(new ResourceState.Key(owner(t),ENERGY)).value();}
    static void select(ProjectileGameTest.Harness t,boolean selected){t.runtime.abilities(new AbilityChange(owner(t),state(t).abilities().getOrDefault(owner(t),AbilityLoadout.EMPTY),selected?new AbilityLoadout(Map.of(SLOT,ABILITY)):AbilityLoadout.EMPTY));}
    static void cast(ProjectileGameTest.Harness t){t.h.assertValueEqual(t.runtime.useAbility(t.owner,SLOT).outcome(),AbilityUse.Outcome.ACCEPTED,"accepted blade cast");}
    static Optional<BuffInstance> status(ProjectileGameTest.Harness t,LivingEntity target,String name){return state(t).buffs().instances().values().stream().filter(b->b.definition().id().equals("chorus_d2:"+name)&&b.key().holder().equals(target.getUUID().toString())).findFirst();}
    static void hit(ProjectileGameTest.Harness t,int flight){var p=t.projectiles.get(flight);int before=t.hits.size();for(int i=0;i<40&&t.hits.size()==before&&!p.isRemoved();i++)p.tick();t.h.assertValueEqual(t.hits.size(),before+1,"one actual blade hit");}

    @GameCase public void fourthPhysicalTargetReceivesDamageAndSlowBeforeFlightEnds(GameTestHelper h)throws Exception{
        try(var t=new ProjectileGameTest.Harness(h,"withering_blade",WitheringBladeGameTest::prepare,true)){
            for(int i=0;i<5;i++){var target=ThreadedSpikeGameTest.target(t,2.5,44+3*i,3.5,1000);target.addTag("chorus_d2:elite");}
            select(t,true);cast(t);cast(t);near(h,energy(t),0,"two charges paid");h.assertValueEqual(t.runtime.useAbility(t.owner,SLOT).outcome(),AbilityUse.Outcome.INSUFFICIENT_ENERGY,"third cast denied");
            var p=t.projectiles.getFirst();for(int i=0;i<40&&!p.isRemoved();i++)p.tick();h.assertTrue(p.isRemoved()&&p.progress().sequence()==4,"fourth contact terminates flight");h.assertValueEqual(t.hits.size(),4,"four actual targets");
            for(int i=0;i<4;i++){near(h,t.entities.get(i).getHealth(),704,"actual calibrated 296 damage");h.assertValueEqual(status(t,t.entities.get(i),"slow").orElseThrow().count(),60,"each hit applies sixty Slow");h.assertValueEqual(t.hits.get(i).source().ability(),ABILITY,"ability credit");}
            near(h,t.entities.get(4).getHealth(),1000,"fifth target untouched");h.assertTrue(status(t,t.entities.get(4),"slow").isEmpty(),"fifth target slowed");
        }h.succeed();
    }
    @GameCase public void twoUnequippedBladesApplyCurrentDuranceThenFreezeWithFinalCastCredit(GameTestHelper h)throws Exception{
        try(var t=new ProjectileGameTest.Harness(h,"withering_blade",WitheringBladeGameTest::prepare,true)){
            var target=ThreadedSpikeGameTest.target(t,2.5,46,3.5,1000);target.addTag("chorus_d2:elite");select(t,true);cast(t);cast(t);select(t,false);t.runtime.bind(DuranceGameTest.fragment("own",t.owner));
            hit(t,0);var slow=status(t,target,"slow").orElseThrow();h.assertValueEqual(slow.deadline()-state(t).buffs().timeMicros(),7_000_000L,"actual Durance extension");near(h,target.getAttributeValue(Attributes.MOVEMENT_SPEED),.1,"native Slow penalty");
            hit(t,1);near(h,target.getHealth(),408,"two blade damage receipts");h.assertTrue(status(t,target,"slow").isEmpty(),"Freeze did not consume Slow");var freeze=status(t,target,"freeze").orElseThrow();h.assertValueEqual(freeze.origin(),t.hits.getLast().source(),"final caster credit");h.assertTrue(!slow.origin().equals(freeze.origin()),"distinct cast identity lost");h.assertValueEqual(freeze.deadline()-state(t).buffs().timeMicros(),6_000_000L,"Durance must not extend Freeze");h.assertValueEqual(NativeMovementGameTest.mask(target),15,"native Freeze controls");
            h.assertTrue(state(t).sources().values().stream().noneMatch(s->s.bundle().equals("chorus_d2:slow_application")),"temporary invocation leaked a live source");
        }h.succeed();
    }
    @GameCase public void actualGuardianReceivesSeventyTwoDamageAndFortySlowEvenInPve(GameTestHelper h)throws Exception{
        try(var t=new ProjectileGameTest.Harness(h,"withering_blade",WitheringBladeGameTest::prepare,true)){
            var target=NativeMeleeGameTest.player(h);t.entities.add(target);target.setPos(h.absoluteVec(new Vec3(2.5,46,3.5)));target.setNoGravity(true);target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);target.setHealth(1000);
            select(t,true);cast(t);cast(t);t.runtime.bind(DuranceGameTest.fragment("own",t.owner));hit(t,0);hit(t,1);near(h,target.getHealth(),856,"two Guardian hits");var slow=status(t,target,"slow").orElseThrow();h.assertValueEqual(slow.count(),80,"two Guardian blades cannot invent a freeze");h.assertValueEqual(slow.deadline()-state(t).buffs().timeMicros(),2_000_000L,"Guardian Durance duration");h.assertTrue(status(t,target,"freeze").isEmpty(),"eighty stacks froze Guardian");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:withering_recovery",maxTicks=80)
    public void realTicksRechargeOneSharedAccountWhileEmptyFlightsExpire(GameTestHelper h)throws Exception{
        var t=new ProjectileGameTest.Harness(h,"withering_blade",WitheringBladeGameTest::prepare,true);try{
            select(t,true);cast(t);cast(t);long start=state(t).buffs().timeMicros();
            t.finish(45,()->{near(h,energy(t),(state(t).buffs().timeMicros()-start)/1_000_000.0/145.2,"two charges recharge sequentially under actual ticks");h.assertTrue(t.projectiles.stream().allMatch(p->p.isRemoved()),"calibrated flights did not expire");h.assertTrue(t.hits.isEmpty(),"empty flight produced damage");});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase public void unknownRealDamageKeepsChargeSpentAndCannotApplySlowOrRepeatHit(GameTestHelper h)throws Exception{
        try(var t=new ProjectileGameTest.Harness(h,"withering_blade",WitheringBladeGameTest::prepare,true)){
            var target=ThreadedSpikeGameTest.target(t,2.5,46,3.5,1000);select(t,true);cast(t);t.failAfterDamage=true;var p=t.projectiles.getFirst();for(int i=0;i<40&&!p.isRemoved();i++)p.tick();
            h.assertTrue(t.runtime.failure().isPresent()&&p.isRemoved(),"unknown result did not stop runtime and flight");near(h,target.getHealth(),704,"committed hit retained");near(h,energy(t),1,"charge remains spent");h.assertTrue(status(t,target,"slow").isEmpty(),"unknown hit applied Slow");p.tick();h.assertValueEqual(t.hits.size(),1,"damage replayed");
        }h.succeed();
    }
}
