package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real server movement, proximity observations and knife casts; timing/distance remain explicit calibration. */
public class GamblersDodgeGameTest {
    static final String D="chorus_d2:", SLOT=D+"class", ABILITY=D+"gamblers_dodge";
    static void merge(JsonObject data,JsonObject part){for(var e:part.entrySet())if(!e.getKey().equals("version")){if(e.getValue().isJsonArray()){if(!data.has(e.getKey()))data.add(e.getKey(),new JsonArray());e.getValue().getAsJsonArray().forEach(data.getAsJsonArray(e.getKey())::add);}else data.add(e.getKey(),e.getValue());}}
    static ProjectileGameTest.Harness open(GameTestHelper h)throws Exception {
        var t=new ProjectileGameTest.Harness(h,"lightweight_knife",data->{
            LightweightKnifeGameTest.prepare(data);var dodge=ThreadedSpikeGameTest.json("gamblers_dodge");var calibration=ThreadedSpikeGameTest.json("gamblers_dodge_test_calibration");
            calibration.getAsJsonObject("parameters").entrySet().forEach(e->dodge.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value",e.getValue()));merge(data,dodge);merge(data,calibration.getAsJsonObject("program"));
        },true);
        t.owner.getFoodData().setFoodLevel(0);select(t,true);return t;
    }
    static String id(ProjectileGameTest.Harness t){return t.owner.getUUID().toString();}
    static void select(ProjectileGameTest.Harness t,boolean dodge){var slots=new HashMap<String,String>();slots.put(D+"melee",D+"lightweight_knife");if(dodge)slots.put(SLOT,ABILITY);t.runtime.abilities(new AbilityChange(id(t),LightweightKnifeGameTest.state(t).abilities().getOrDefault(id(t),AbilityLoadout.EMPTY),new AbilityLoadout(slots)));}
    static void stat(ProjectileGameTest.Harness t,double points){t.runtime.bind(new EffectSource("stat","test:solar_melee_stat",id(t),new BuffInstance.Origin(id(t),"stat","",""),Set.of(),Map.of("points",new Measure(points,Unit.STAT_POINT))));}
    static ResourceState account(ProjectileGameTest.Harness t,String name){return LightweightKnifeGameTest.resource(t,name);}
    static ResourceState classEnergy(ProjectileGameTest.Harness t){return LightweightKnifeGameTest.state(t).resources().get(new ResourceState.Key(id(t),D+"gamblers_dodge_energy"));}
    static void empty(ProjectileGameTest.Harness t){t.owner.setXRot(-90);LightweightKnifeGameTest.cast(t);LightweightKnifeGameTest.cast(t);near(t.h,account(t,"uses").value(),0,"knives not spent");}
    static void dodge(ProjectileGameTest.Harness t){t.owner.setOnGround(true);t.h.assertValueEqual(t.runtime.useAbility(t.owner,SLOT).outcome(),AbilityUse.Outcome.ACCEPTED,"Gambler accepted");}
    static LivingEntity enemyAt(ProjectileGameTest.Harness t,double x,double z){var e=t.cow(2.5,40,3.5);e.setPos(t.owner.getX()+x,t.owner.getBoundingBox().getCenter().y-e.getBoundingBox().getYsize()/2,t.owner.getZ()+z);return e;}
    @GameCase(environment="chorus_gametest:gambler_near",maxTicks=30)
    public void actualDodgeReturnsTwoKnivesAndThoseChargesLaunchPhysicalDamage(GameTestHelper h)throws Exception {
        var t=open(h);try{
            stat(t,100);empty(t);var before=t.owner.position();dodge(t);near(h,t.owner.getZ(),before.z+3,"actual calibrated displacement");near(h,classEnergy(t).value(),0,"dodge payment");enemyAt(t,4,0);
            select(t,false); // Accepted detached return keeps the cast, while routing the current melee.
            t.finish(6,()->{near(h,account(t,"uses").value(),2,"nearby enemy must return two charges");near(h,account(t,"progress").value(),0,"full charges retained progress");
                var target=LightweightKnifeGameTest.target(t,.5,1024);target.setPos(t.owner.getX(),target.getY(),t.owner.getZ()+2);t.owner.setXRot(0);t.owner.setYRot(0);LightweightKnifeGameTest.cast(t);LightweightKnifeGameTest.hit(t,2);near(h,target.getHealth(),616,"restored knife actual damage");target.discard();t.owner.setXRot(-90);LightweightKnifeGameTest.cast(t);h.assertValueEqual(t.runtime.useAbility(t.owner,D+"melee").outcome(),AbilityUse.Outcome.INSUFFICIENT_ENERGY,"invented third knife");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:gambler_far",maxTicks=30)
    public void actualAlliesAndTargetsOutsideFifteenMetersDoNotDoubleTheReturn(GameTestHelper h)throws Exception {
        var t=open(h);var scoreboard=h.getLevel().getScoreboard();var team=scoreboard.addPlayerTeam("gambler-"+UUID.randomUUID());
        try{stat(t,100);empty(t);dodge(t);var ally=enemyAt(t,2,0);scoreboard.addPlayerToTeam(t.owner.getScoreboardName(),team);scoreboard.addPlayerToTeam(ally.getScoreboardName(),team);enemyAt(t,15.001,0);
            h.runAfterDelay(6,()->{try{t.runtime.prepare();h.assertTrue(t.runtime.failure().isEmpty(),"return failed");near(h,account(t,"uses").value(),1,"allied/outside target doubled energy");h.assertTrue(account(t,"progress").value()<.02,"unexpected excess energy");h.assertValueEqual(t.queries.size(),1,"return queried repeatedly");h.succeed();}finally{scoreboard.removePlayerTeam(team);t.close();}});
        }catch(Throwable e){scoreboard.removePlayerTeam(team);t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:gambler_wall",maxTicks=30)
    public void actualWallClipsDodgeAndAnEnemyAtFifteenMetersStillDoublesReturn(GameTestHelper h)throws Exception {
        var t=open(h);try{stat(t,100);empty(t);var before=t.owner.position();
            for(int y=0;y<3;y++){var p=BlockPos.containing(before).offset(0,y,1);t.blocks.put(p,h.getLevel().getBlockState(p));h.getLevel().setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());}
            dodge(t);h.assertTrue(t.owner.getZ()>before.z&&t.owner.getZ()<before.z+1,"wall did not clip body");near(h,t.owner.getY(),before.y,"dodge stepped onto wall");enemyAt(t,15,0);
            t.finish(6,()->{near(h,account(t,"uses").value(),2,"inclusive fifteen-meter body query");h.assertTrue(classEnergy(t).value()<.02,"wall refunded class payment");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:gambler_ophidia",maxTicks=40)
    public void equippedOphidiaRefreshesOnRealDodgeAndOneCycleRestoresBothKnives(GameTestHelper h)throws Exception {
        var t=open(h);try{stat(t,50);LightweightKnifeGameTest.equip(t);var victim=LightweightKnifeGameTest.target(t,.5,1);t.owner.setXRot(0);t.owner.setYRot(0);LightweightKnifeGameTest.cast(t);LightweightKnifeGameTest.hit(t,0);victim.discard();t.owner.setXRot(-90);LightweightKnifeGameTest.cast(t);
            h.assertValueEqual(LightweightKnifeGameTest.buff(t,"scissor_fingers").orElseThrow().count(),1,"actual knife kill did not feed equipped Ophidia");long oldDeadline=LightweightKnifeGameTest.buff(t,"scissor_fingers").orElseThrow().deadline();
            h.runAfterDelay(8,()->{try{dodge(t);enemyAt(t,3,0);var buff=LightweightKnifeGameTest.buff(t,"scissor_fingers").orElseThrow();h.assertValueEqual(buff.count(),1,"dodge increased stacks");h.assertTrue(buff.deadline()>oldDeadline,"dodge did not refresh existing stacks");
                t.finish(6,()->{near(h,account(t,"uses").value(),2,"one cycle did not restore linked knives");near(h,account(t,"uses").capacity(),2,"innate and exotic second charge stacked");});
            }catch(Throwable e){t.close();throw e;}});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase public void airborneNativeInputRejectsBeforeMovementOrPayment(GameTestHelper h)throws Exception {
        try(var t=open(h)){t.owner.setOnGround(false);var before=t.owner.position();h.assertValueEqual(t.runtime.useAbility(t.owner,SLOT).outcome(),AbilityUse.Outcome.CONDITION,"airborne dodge accepted");near(h,t.owner.position().distanceTo(before),0,"rejected dodge moved");near(h,classEnergy(t).value(),1,"rejected dodge paid");h.assertTrue(t.queries.isEmpty(),"rejected dodge queried enemies");}h.succeed();
    }
    @GameCase public void unknownNativeDisplacementRetainsMovementAndCostWithoutEnergyOrReplay(GameTestHelper h)throws Exception {
        try(var t=open(h)){stat(t,100);empty(t);var before=t.owner.position();t.failAfterDisplacement=true;try{dodge(t);}catch(IllegalStateException expected){}
            h.assertTrue(t.runtime.failure().isPresent(),"unknown native displacement did not stop");near(h,t.owner.getZ(),before.z+3,"actual move rolled back");near(h,classEnergy(t).value(),0,"payment rolled back");near(h,account(t,"uses").value(),0,"unknown move granted melee energy");t.runtime.prepare();near(h,t.owner.getZ(),before.z+3,"unknown move replayed");h.assertTrue(t.queries.isEmpty(),"return ran after unknown movement");
        }h.succeed();
    }
}
