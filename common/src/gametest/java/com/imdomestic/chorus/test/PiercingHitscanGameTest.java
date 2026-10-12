package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.projectile.Hitscan;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;

public class PiercingHitscanGameTest {
    static void groupDamage(JsonElement value) {
        if(value.isJsonArray()){value.getAsJsonArray().forEach(PiercingHitscanGameTest::groupDamage);return;}
        if(!value.isJsonObject())return;var o=value.getAsJsonObject();
        if(o.has("type")&&o.get("type").getAsString().equals("chorus:damage_snapshot"))o.addProperty("pellet","impact");
        o.entrySet().forEach(e->groupDamage(e.getValue()));
    }
    static ProjectileGameTest.Harness open(GameTestHelper h,int pierces)throws Exception {
        return PrecisionAmmoGameTest.open(h,data->{
            HitscanGameTest.instant(data);var fire=data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire");var before=fire.getAsJsonArray("on_fire");var ray=before.get(4).getAsJsonObject();
            ray.add("pierces",JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":"+pierces+",\"unit\":\"count\"}"));
            ray.add("shot",JsonParser.parseString("{\"binding\":\"group\",\"pellet\":{\"type\":\"chorus:constant\",\"value\":0,\"unit\":\"count\"}}"));groupDamage(ray.getAsJsonArray("do"));
            var next=new JsonArray();for(int i=0;i<4;i++)next.add(before.get(i));
            next.add(JsonParser.parseString("{\"action\":{\"type\":\"chorus:begin_shot\",\"pellets\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"count\"},\"lifetime\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"second\"}},\"as\":\"group\"}"));next.add(ray);fire.add("on_fire",next);
            for(var bundle:data.getAsJsonArray("bundles"))if(bundle.getAsJsonObject().get("id").getAsString().equals("test:projectile")) {
                var b=bundle.getAsJsonObject();if(!b.has("rules"))b.add("rules",new JsonArray());
                b.getAsJsonArray("rules").add(JsonParser.parseString("{\"id\":\"piercing_summary\",\"on\":\"chorus:shot_resolved\",\"if\":{\"type\":\"chorus:all\",\"of\":[{\"type\":\"chorus:compare\",\"op\":\"eq\",\"left\":{\"type\":\"chorus:event_number\",\"name\":\"pellets_hit\",\"unit\":\"count\"},\"right\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"count\"}},{\"type\":\"chorus:compare\",\"op\":\"eq\",\"left\":{\"type\":\"chorus:event_number\",\"name\":\"targets_hit\",\"unit\":\"count\"},\"right\":{\"type\":\"chorus:constant\",\"value\":2,\"unit\":\"count\"}}]},\"do\":[{\"type\":\"chorus:play_cue\",\"cue\":\"test:one_pellet_two_targets\"}]}"));
            }
        });
    }
    static List<LivingEntity> line(ProjectileGameTest.Harness t,int count) {
        var result=new ArrayList<LivingEntity>();for(int i=0;i<count;i++){var target=PrecisionAmmoGameTest.target(t,true);target.setPos(target.position().add(0,0,i*2));result.add(target);}return result;
    }
    static void fire(ProjectileGameTest.Harness t){t.h.assertValueEqual(t.runtime.fire(t.owner).outcome(),WeaponFire.Outcome.ACCEPTED,"paid ray accepted");t.h.assertTrue(t.runtime.failure().isEmpty(),"ray failed: "+t.runtime.failure());}
    @GameCase(environment="chorus_gametest:piercing_ray_two")
    public void oneBulletHitsTwoEnemiesAndCountsOneMemberAcrossBoth(GameTestHelper h)throws Exception {
        try(var t=open(h,1)) {
            var targets=line(t,3);fire(t);near(h,targets.get(0).getHealth(),985,"first precision damage");near(h,targets.get(1).getHealth(),985,"second precision damage");near(h,targets.get(2).getHealth(),1000,"penetration limit");
            h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),11,"one bullet paid");h.assertValueEqual(t.hits.size(),2,"two damage receipts");
            h.assertTrue(t.cues.stream().anyMatch(c->c.cue().equals("test:one_pellet_two_targets")),"one member/two target summary missing");
            h.assertTrue(t.runtime.state().engine().domain().shotGroups().isEmpty(),"finished group leaked");h.assertTrue(t.projectiles.isEmpty(),"piercing ray spawned entities");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:piercing_ray_perks",maxTicks=40)
    public void confirmedPrecisionContactsDriveBothAmmoPerksWithoutDuplicatingFireCost(GameTestHelper h)throws Exception {
        var t=open(h,1);try {
            line(t,2);fire(t);near(h,PrecisionAmmoGameTest.count(t,PrecisionAmmoGameTest.TT,"a"),2,"two actual precision contacts");
            RampageGameTest.at(t,4,()->{try(t){fire(t);h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),12,"two paid rounds plus capped three generated rounds");near(h,PrecisionAmmoGameTest.count(t,PrecisionAmmoGameTest.TT,"a"),1,"third hit paid one refund, fourth restarted");near(h,PrecisionAmmoGameTest.count(t,PrecisionAmmoGameTest.FT,"a"),0,"fourth hit paid two refund");h.assertValueEqual(t.hits.size(),4,"two rays four receipts");h.assertValueEqual(PrecisionAmmoGameTest.state(t).ammunition().get("a").reserve().orElseThrow().rounds(),30,"generation does not spend reserve");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:piercing_ray_wall")
    public void piercingStopsAtTerrainAndKeepsTheEarlierEntityHit(GameTestHelper h)throws Exception {
        try(var t=open(h,3)) {
            var targets=line(t,3);var p=BlockPos.containing(t.owner.getX(),t.owner.getEyeY(),targets.get(1).getZ()-1);t.blocks.put(p,h.getLevel().getBlockState(p));h.getLevel().setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());
            fire(t);near(h,targets.getFirst().getHealth(),985,"known front target");near(h,targets.get(1).getHealth(),1000,"target behind wall");near(h,targets.get(2).getHealth(),1000,"far target behind wall");h.assertValueEqual(t.hits.size(),1,"terrain penetration forbidden");h.assertTrue(PrecisionAmmoGameTest.state(t).shotGroups().isEmpty(),"wall failed to close member");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:piercing_ray_geometry")
    public void laterTargetMotionDoesNotRewriteTheAlreadyObservedInstantaneousPath(GameTestHelper h)throws Exception {
        try(var t=open(h,1)) {
            var targets=line(t,2);t.beforeWorld=request->{if(request.command() instanceof DamageCommand && t.hits.isEmpty())targets.get(1).setPos(targets.get(1).position().add(6,0,0));};
            fire(t);h.assertValueEqual(t.hits.size(),2,"scene was queried again after first damage");near(h,targets.get(1).getHealth(),985,"captured weakpoint geometry retained");h.assertTrue(targets.get(1).getX()>t.owner.getX()+5,"test did not move second target");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:piercing_ray_zero")
    public void explicitZeroRetainsSingleContactAndLargeBudgetEndsAtTheFiniteScene(GameTestHelper h)throws Exception {
        try(var t=open(h,0)) {
            var targets=line(t,3);fire(t);h.assertValueEqual(t.hits.size(),1,"zero means no extra penetration");near(h,targets.get(1).getHealth(),1000,"zero policy hit second target");
            var ray=HitscanGameTest.query(t,HitscanGameTest.point(t,t.owner.getEyePosition()),HitscanGameTest.forward(t),20,0);var result=MinecraftHitscan.trace(h.getLevel(),new Hitscan.PiercingQuery(ray,Integer.MAX_VALUE));
            h.assertValueEqual(result.contacts().size(),4,"three contacts plus terminal range");h.assertValueEqual(result.contacts().getLast().outcome(),Hitscan.Outcome.RANGE,"finite path terminal");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:piercing_ray_unknown")
    public void unknownFirstDamageKeepsOnePaymentAndNeverDamagesTheCapturedSecondTarget(GameTestHelper h)throws Exception {
        try(var t=open(h,1)) {
            var targets=line(t,2);t.failAfterDamage=true;try{t.runtime.fire(t.owner);}catch(IllegalStateException expected){}
            h.assertTrue(t.runtime.failure().isPresent(),"unknown world result must stop runtime");near(h,targets.get(0).getHealth(),985,"first write happened");near(h,targets.get(1).getHealth(),1000,"second captured contact must not run");
            h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),11,"accepted payment retained");try{t.runtime.fire(t.owner);}catch(IllegalStateException expected){}h.assertValueEqual(t.hits.size(),1,"unknown result replayed");h.succeed();
        }
    }
}
