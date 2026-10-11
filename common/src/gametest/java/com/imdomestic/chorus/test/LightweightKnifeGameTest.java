package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Actual server swept collisions classify explicitly bound target regions; no synthetic impact injection. */
public class LightweightKnifeGameTest {
    static final String D="chorus_d2:",ABILITY=D+"lightweight_knife",SLOT=D+"melee";
    static void prepare(JsonObject data){
        var params=data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters");ThreadedSpikeGameTest.json("lightweight_knife_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e->params.getAsJsonObject(e.getKey()).add("value",e.getValue()));
        for(String name:List.of("character_stats","combat_damage","solar_melee_energy","ophidia_spathe","ophidia_spathe_inputs","radiant","empowering_damage","ability_stat_damage","lightweight_knife_precision_test_calibration")){
            var part=ThreadedSpikeGameTest.json(name);for(var e:part.entrySet())if(!e.getKey().equals("version")){
                if(e.getValue().isJsonArray()){if(!data.has(e.getKey()))data.add(e.getKey(),new JsonArray());e.getValue().getAsJsonArray().forEach(v->data.getAsJsonArray(e.getKey()).add(v));}else data.add(e.getKey(),e.getValue());
            }
        }
        for(String id:List.of("test:projectile","test:power")){var b=new JsonObject();b.addProperty("id",id);data.getAsJsonArray("bundles").add(b);}
    }
    static ProjectileGameTest.Harness open(GameTestHelper h)throws Exception{return open(h,false);}
    static ProjectileGameTest.Harness open(GameTestHelper h,boolean contactCue)throws Exception{
        var t=new ProjectileGameTest.Harness(h,"lightweight_knife",data->{prepare(data);
            var actions=data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use");
            var contact=actions.get(actions.size()-1).getAsJsonObject().getAsJsonArray("do");
            for(String reason:List.of("expired","unloaded")) contact.add(JsonParser.parseString("{\"if\":{\"type\":\"chorus:result_flag\",\"binding\":\"impact\",\"field\":\""+reason+"\"},\"then\":[{\"type\":\"chorus:play_cue\",\"cue\":\"test:"+reason+"\"}]}"));
            if(contactCue){var body=data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use");var projectile=body.get(body.size()-1).getAsJsonObject();var before=new JsonArray();before.add(JsonParser.parseString("{\"type\":\"chorus:play_cue\",\"cue\":\"test:contact\"}"));projectile.getAsJsonArray("do").forEach(before::add);projectile.add("do",before);}},true);
        t.owner.setXRot(0);t.owner.setYRot(0);t.owner.getFoodData().setFoodLevel(0);select(t,true);return t;
    }
    static String owner(ProjectileGameTest.Harness t){return t.owner.getUUID().toString();}
    static EffectState state(ProjectileGameTest.Harness t){return t.runtime.state().engine().domain();}
    static void select(ProjectileGameTest.Harness t,boolean selected){t.runtime.abilities(new AbilityChange(owner(t),state(t).abilities().getOrDefault(owner(t),AbilityLoadout.EMPTY),selected?new AbilityLoadout(Map.of(SLOT,ABILITY)):AbilityLoadout.EMPTY));}
    static void cast(ProjectileGameTest.Harness t){t.h.assertValueEqual(t.runtime.useAbility(t.owner,SLOT).outcome(),AbilityUse.Outcome.ACCEPTED,"knife accepted");}
    static ResourceState resource(ProjectileGameTest.Harness t,String name){return state(t).resources().get(new ResourceState.Key(owner(t),D+"solar_melee_"+name));}
    static Optional<BuffInstance> buff(ProjectileGameTest.Harness t,String name){return state(t).buffs().instances().values().stream().filter(b->b.key().holder().equals(owner(t))&&b.definition().id().equals(D+name)).findFirst();}
    static void region(ProjectileGameTest.Harness t,LivingEntity target){String id=target.getUUID().toString();t.runtime.bind(new EffectSource("region/"+id,"test:knife_region",id,new BuffInstance.Origin(id,"region/"+id,"",""),Set.of(),Map.of("min_x",new Measure(-1,Unit.MULTIPLIER),"max_x",new Measure(2,Unit.MULTIPLIER),"min_y",new Measure(.75,Unit.MULTIPLIER),"max_y",new Measure(1.125,Unit.MULTIPLIER),"min_z",new Measure(-1,Unit.MULTIPLIER),"max_z",new Measure(2,Unit.MULTIPLIER))));}
    static LivingEntity target(ProjectileGameTest.Harness t,double relativeY,float health){var e=ThreadedSpikeGameTest.target(t,2.5,40,8.5,health);e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);e.setHealth(health);e.setPos(t.owner.getX(),t.owner.getEyeY()-e.getBoundingBox().getYsize()*relativeY,e.getZ());return e;}
    static void hit(ProjectileGameTest.Harness t,int flight){
        var p=t.projectiles.get(flight);int before=t.hits.size();for(int i=0;i<40&&!p.isRemoved();i++)p.tick();
        t.h.assertTrue(p.isRemoved(),"knife continued after first contact");
        t.h.assertValueEqual(t.hits.size(),before+1,"actual knife contact did not damage exactly once; flight="+flight+", ticks="+p.tickCount
                +", owner="+t.owner.position()+", end="+p.position()+", progress="+p.progress()+", cues="+t.cues.stream().map(c->c.cue()).toList()+", failure="+t.runtime.failure());
    }
    static void equip(ProjectileGameTest.Harness t){var gear=new ItemStack(Items.STRING);gear.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear("ophidia","test:ophidia",Map.of(),OphidiaSpatheGameTest.calibration()));t.owner.getInventory().setItem(0,gear);var e=PlayerEquipment.get(t.owner);e.swap(t.owner,"test:chest",0,e.revision());}
    @GameCase public void physicalBodyAndConfiguredPrecisionContactsApplyKnownDamageAndRadiant(GameTestHelper h)throws Exception{
        try(var t=open(h)){
            var body=target(t,.5,1024);region(t,body);cast(t);h.assertTrue(t.hits.isEmpty(),"knife became hitscan");hit(t,0);near(h,body.getHealth(),616,"408 body damage");h.assertTrue(buff(t,"radiant").isEmpty(),"body hit granted Radiant");body.discard();
            var head=target(t,.9,1024);region(t,head);cast(t);hit(t,1);near(h,head.getHealth(),412,"612 precision damage");h.assertTrue(t.hits.getLast().tags().contains("chorus:precision"),"precision credit missing");h.assertValueEqual(buff(t,"radiant").orElseThrow().deadline()-state(t).buffs().timeMicros(),10_000_000L,"ten-second Radiant");near(h,resource(t,"uses").value(),0,"two charges not paid");
        }h.succeed();
    }
    @GameCase public void actualGuardianAndUnconfiguredTargetsUseTheirOwnDamageAndRegionData(GameTestHelper h)throws Exception{
        try(var t=open(h)){
            var unclassified=target(t,.9,1024);cast(t);hit(t,0);near(h,unclassified.getHealth(),616,"unconfigured target received guessed headshot");h.assertTrue(buff(t,"radiant").isEmpty(),"unconfigured target granted Radiant");unclassified.discard();
            var guardian=NativeMeleeGameTest.player(h);t.entities.add(guardian);guardian.setNoGravity(true);guardian.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);guardian.setHealth(1000);guardian.setPos(t.owner.getX(),t.owner.getEyeY()-guardian.getBoundingBox().getYsize()*.9,h.absoluteVec(new Vec3(0,0,8.5)).z);region(t,guardian);cast(t);hit(t,1);near(h,guardian.getHealth(),850,"Guardian precision damage must be 100*1.5 even in PvE mode");
        }h.succeed();
    }
    @GameCase public void contactGeometrySurvivesTargetMovementAndIntrinsicPrecisionSurvivesDeselection(GameTestHelper h)throws Exception{
        try(var t=open(h,true)){
            var enemy=target(t,.9,1024);region(t,enemy);cast(t);select(t,false);t.onCue=cue->{if(cue.cue().equals("test:contact"))enemy.setPos(enemy.position().add(8,8,0));};hit(t,0);
            near(h,enemy.getHealth(),412,"contact read a moved target box or lost its captured intrinsic factor");h.assertTrue(buff(t,"radiant").isPresent(),"accepted precision hit lost Radiant after selection change");
        }h.succeed();
    }
    @GameCase public void physicalKnifeKillsFeedEquippedOphidiaAndNextKnifeUsesItsCurrentBonus(GameTestHelper h)throws Exception{
        try(var t=open(h)){
            equip(t);near(h,resource(t,"uses").capacity(),2,"Ophidia added a third intrinsic knife");var first=target(t,.5,200);cast(t);hit(t,0);h.assertTrue(first.isDeadOrDying(),"knife failed to kill");h.assertValueEqual(buff(t,"scissor_fingers").orElseThrow().count(),1,"physical knife kill did not credit Ophidia");first.discard();
            var second=target(t,.9,1024);region(t,second);cast(t);hit(t,1);near(h,second.getHealth(),1024-612*1.67,"current Ophidia bonus missing on physical precision hit");h.assertValueEqual(t.runtime.useAbility(t.owner,SLOT).outcome(),AbilityUse.Outcome.INSUFFICIENT_ENERGY,"third knife bypassed payment");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:lightweight_ticks",maxTicks=65)
    public void actualTicksAdvanceTheSharedMeterAndEndEmptyFlights(GameTestHelper h)throws Exception{
        var t=open(h);
        try {
            cast(t); t.owner.setXRot(-90); cast(t); long start=t.runtime.nowMicros();
            t.finish(45,()->{
                near(h,resource(t,"progress").value(),(t.runtime.nowMicros()-start)/1_000_000.0/145.2,"native cooldown rate");
                near(h,resource(t,"uses").value(),0,"partial meter became usable");
                h.assertTrue(t.projectiles.stream().allMatch(p->p.isRemoved()),"empty knives retained executable flights");
                h.assertValueEqual(t.projectiles.get(1).tickCount,40,"vertical knife did not complete exactly two seconds of physical simulation");
                h.assertValueEqual(t.cues.size(),2,"each flight must finish once");
                h.assertTrue(t.cues.stream().anyMatch(c->c.cue().equals("test:expired")),"vertical knife never expired");
                h.assertTrue(t.hits.isEmpty(),"empty flight damaged a target");
            });
        } catch(Exception|Error e) { t.close(); throw e; }
    }
    @GameCase public void aRealWallConsumesTheKnifeBeforeItsTargetWithoutRefund(GameTestHelper h)throws Exception{
        try(var t=open(h)){
            var enemy=target(t,.9,1024);region(t,enemy);for(int y=40;y<44;y++){var p=h.absolutePos(new BlockPos(2,y,6));t.blocks.put(p,h.getLevel().getBlockState(p));h.getLevel().setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());}cast(t);var p=t.projectiles.getFirst();for(int i=0;i<40&&!p.isRemoved();i++)p.tick();h.assertTrue(p.isRemoved(),"wall failed to stop knife");h.assertTrue(t.hits.isEmpty(),"knife damaged through wall");near(h,enemy.getHealth(),1024,"wall target damaged");near(h,resource(t,"uses").value(),1,"blocked knife refunded");
        }h.succeed();
    }
    @GameCase public void unknownPhysicalDamageKeepsHealthLossAndPaymentWithoutRadiantOrReplay(GameTestHelper h)throws Exception{
        try(var t=open(h)){
            var enemy=target(t,.9,1024);region(t,enemy);cast(t);t.failAfterDamage=true;var p=t.projectiles.getFirst();for(int i=0;i<40&&!p.isRemoved();i++)p.tick();h.assertTrue(p.isRemoved()&&t.runtime.failure().isPresent(),"unknown damage did not stop flight/runtime");near(h,enemy.getHealth(),412,"actual damage rolled back");near(h,resource(t,"uses").value(),1,"paid charge refunded");h.assertTrue(buff(t,"radiant").isEmpty(),"unknown damage granted Radiant");p.tick();h.assertValueEqual(t.hits.size(),1,"unknown contact replayed");
        }h.succeed();
    }
}
