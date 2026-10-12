package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.ExplosivePayloadGameTest.*;
import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

/** Real native collision/health and explicit synthetic fuse calibration; bow draw is not implemented here. */
public class ExplosiveHeadGameTest {
    static ProjectileGameTest.Harness openHead(GameTestHelper h,Consumer<JsonObject> edit)throws Exception{
        var t=new ProjectileGameTest.Harness(h,"explosive_head_weapon",data->{RadiantGameTest.merge(data,"explosive_head","explosive_head_test_calibration","explosive_payload","explosive_payload_test_calibration","weapon_stats");edit.accept(data);},true);
        try{
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX()+3.5,t.owner.getY(),t.owner.chunkPosition().getMinBlockZ()+3.5);t.owner.setXRot(-90);t.owner.setYRot(0);t.owner.setGameMode(GameType.SURVIVAL);
            var gear=new Loadout.Gear("a","test:bow",Map.of("perk","normal"));var stack=new ItemStack(Items.BOW);stack.set(ChorusComponents.EQUIPMENT.get(),gear);t.owner.getInventory().setItem(0,stack);var e=PlayerEquipment.get(t.owner);e.swap(t.owner,"test:primary",0,e.revision());draw(t,"primary");return t;
        }catch(Exception|Error error){t.close();throw error;}
    }
    static void delay(JsonObject data,double seconds){
        for(var entry:data.getAsJsonArray("profiles")){
            var profile=entry.getAsJsonObject();if(!profile.get("id").getAsString().equals("chorus_d2:explosive_head_delay"))continue;
            for(var point:profile.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("curve").getAsJsonArray("points"))point.getAsJsonObject().addProperty("output",seconds);
        }
    }
    @GameCase(environment="chorus_gametest:head_near",maxTicks=25)
    public void nearPhysicalContactHasFourTickFuseAndSeparateNativeComponents(GameTestHelper h)throws Exception{
        var t=openHead(h,_ -> {});try{
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);contact(t,shot);near(h,target.getHealth(),95,"half immediate body");
            at(t,3,()->{settled(t);near(h,target.getHealth(),95,"not due at 150 ms");});
            at(t,4,()->{try(t){settled(t);near(h,target.getHealth(),88.5,"native body plus PvE explosion");h.assertValueEqual(t.hits.size(),2,"separate native receipts");h.assertValueEqual(t.hits.getFirst().group(),t.hits.getLast().group(),"same attack group");h.assertTrue(state(t).attachments().isEmpty()&&state(t).damageGroups().isEmpty(),"operation cleaned up");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:head_far",maxTicks=30)
    public void distantContactUsesSavedMuzzleAndSourceAfterUnequipThenFollowsTarget(GameTestHelper h)throws Exception{
        var t=openHead(h,_ -> {});try{
            IncandescentGameTest.bind(t,"bonus","test:payload_bonus","a");var shot=PugilistGameTest.fire(t);var target=primary(t,shot,15);region(t,target);
            t.runtime.unbind("bonus");var e=PlayerEquipment.get(t.owner);t.owner.getInventory().setItem(0,ItemStack.EMPTY);e.swap(t.owner,"test:primary",0,e.revision());t.owner.setPos(t.owner.getX()+8,t.owner.getY()+8,t.owner.getZ());
            contact(t,shot);near(h,target.getHealth(),77.5,"captured precision direct");var oldPoint=point(shot,15);target.setPos(target.getX()+4,target.getY()+1,target.getZ());var moved=new WorldPosition(oldPoint.dimension(),oldPoint.x()+4,oldPoint.y()+1,oldPoint.z());var next=neighbor(t,moved,1.5);
            at(t,7,()->{settled(t);near(h,target.getHealth(),77.5,"15 m is not due at 350 ms despite moved owner");});
            at(t,8,()->{try(t){settled(t);near(h,target.getHealth(),67.75,"captured full primary blast");near(h,next.getHealth(),95.125,"current radial neighbor");h.assertValueEqual(((TargetQuery.PositionCenter)t.queries.getFirst().center()).position(),Optional.of(moved),"contact offset follows target");h.assertValueEqual(t.hits.getLast().source().weapon(),"a","unequipped original source");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:head_removed",maxTicks=25)
    public void nativeRemovalRetainsHistoricalExplosionWithoutFollowingReplacement(GameTestHelper h)throws Exception{
        var t=openHead(h,_ -> {});try{
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);var next=neighbor(t,point(shot,5),1.5);contact(t,shot);var replacement=TimedPayloadGameTest.replace(t,target);replacement.setPos(replacement.getX()+20,replacement.getY(),replacement.getZ());
            at(t,4,()->{try(t){settled(t);near(h,replacement.getHealth(),100,"new life cannot move old blast");near(h,next.getHealth(),96.75,"historical radial blast");h.assertTrue(t.cues.isEmpty(),"known removal must keep its recorded point");h.assertTrue(state(t).attachments().isEmpty()&&state(t).damageGroups().isEmpty(),"operation cleaned up");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:head_zero",maxTicks=25)
    public void zeroFuseIsImmediateAndNegativeCalibrationCannotIssueNativeDamage(GameTestHelper h)throws Exception{
        for(double seconds:new double[]{0,-.1})try(var t=openHead(h,data->delay(data,seconds))){
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);contact(t,shot);near(h,target.getHealth(),seconds==0?88.5:100,"explicit calibration boundary");h.assertValueEqual(t.hits.size(),seconds==0?2:0,"native write count");
            h.assertValueEqual(t.cues.stream().map(c->c.cue()).toList(),seconds==0?List.of():List.of("test:explosive_head_invalid_delay"),"negative delay diagnostic");h.assertTrue(state(t).attachments().isEmpty()&&state(t).damageGroups().isEmpty(),"no leaked attachment/group");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:head_long",maxTicks=70)
    public void longFuseRetainsAttachmentBeyondOneSecondAndClosesAfterDetonation(GameTestHelper h)throws Exception{
        var t=openHead(h,data->delay(data,2.4));try{
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);contact(t,shot);
            at(t,24,()->{settled(t);near(h,target.getHealth(),95,"long fuse still pending");h.assertValueEqual(state(t).attachments().size(),1,"attachment survives a second");h.assertValueEqual(state(t).damageGroups().size(),1,"damage group survives a second");});
            at(t,48,()->{try(t){settled(t);near(h,target.getHealth(),88.5,"long fuse actual damage");h.assertTrue(state(t).attachments().isEmpty()&&state(t).damageGroups().isEmpty(),"completed long fuse cleanup");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:head_unknown",maxTicks=25)
    public void unknownDelayedNativeReceiptDoesNotReplayTheAppliedExplosion(GameTestHelper h)throws Exception{
        var t=openHead(h,_ -> {});try{
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);contact(t,shot);t.failAfterDamage=true;
            at(t,4,()->{try(t){t.runtime.prepare();h.assertTrue(t.runtime.failure().isPresent(),"unknown delayed outcome stops runtime");near(h,target.getHealth(),88.5,"already applied damage remains");int count=t.operations.size();t.runtime.prepare();h.assertValueEqual(t.operations.size(),count,"no continuation replay");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
}
