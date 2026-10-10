package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

/** Actual player containers, command-fired projectiles, native deaths and source-owned Solar effects. */
public class IncandescentGameTest {
    static void prepare(JsonObject data,boolean credit) {
        for(String name:List.of("solar","solar_test_calibration","incandescent_test_calibration","ember_of_char","character_stats","incandescent_weapon")) {
            var fragment=ThreadedSpikeGameTest.json(name);
            for(var entry:fragment.entrySet()) {
                if(Set.of("version","imports").contains(entry.getKey()))continue;
                if(entry.getValue().isJsonArray()) {
                    if(!data.has(entry.getKey()))data.add(entry.getKey(),new JsonArray());
                    entry.getValue().getAsJsonArray().forEach(e->data.getAsJsonArray(entry.getKey()).add(e));
                }else data.add(entry.getKey(),entry.getValue());
            }
        }
        if(!credit)data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action").add("kill_tags",new JsonArray());
    }
    static ProjectileGameTest.Harness harness(GameTestHelper h,boolean ashes,boolean credit) throws Exception {return harness(h,ashes,credit,_ -> {});}
    static ProjectileGameTest.Harness harness(GameTestHelper h,boolean ashes,boolean credit,java.util.function.Consumer<JsonObject> edit) throws Exception {
        var t=new ProjectileGameTest.Harness(h,"incandescent",d->{prepare(d,credit);edit.accept(d);},true);
        double x=t.owner.chunkPosition().getMinBlockX()+6.5,z=t.owner.chunkPosition().getMinBlockZ()+6.5;
        t.owner.setPos(x,t.owner.getY(),z);RampageGameTest.equip(t);
        bind(t,"solar","chorus_d2:solar_scaling","");if(ashes)bind(t,"ashes","chorus_d2:ember_of_ashes","");return t;
    }
    static void bind(ProjectileGameTest.Harness t,String id,String bundle,String weapon) {
        var owner=t.owner.getUUID().toString();t.runtime.bind(new EffectSource(id,bundle,owner,new BuffInstance.Origin(owner,id,weapon,""),Set.of()));
    }
    static LivingEntity cow(ProjectileGameTest.Harness t,double x,float hp) {
        var e=t.cow(2.5,46,3.5);e.setPos(t.owner.getX()+x,t.owner.getY()+6,t.owner.getZ());
        e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500);e.setHealth(hp);
        t.h.assertTrue(t.resolve(e.getUUID().toString())==e,"Incandescent target must be loaded");return e;
    }
    static EffectState state(ProjectileGameTest.Harness t){return t.runtime.state().engine().domain();}
    static Optional<BuffInstance> scorch(ProjectileGameTest.Harness t,LivingEntity target){return state(t).buffs().instances().values().stream().filter(b->b.definition().id().equals("chorus_d2:scorch")&&b.key().holder().equals(target.getUUID().toString())).findFirst();}
    static List<DamageCommand> hits(ProjectileGameTest.Harness t,String tag){return t.hits.stream().filter(d->d.tags().contains("chorus_d2:"+tag+"_damage")).toList();}
    static void kill(ProjectileGameTest.Harness t,String slot,boolean strong) throws Exception {
        PugilistGameTest.draw(t,slot);var corpse=cow(t,0,5);if(strong)corpse.addTag("chorus_d2:elite");
        var shot=PugilistGameTest.fire(t);PugilistGameTest.impact(t,shot,corpse);
        t.h.assertTrue(t.runtime.failure().isEmpty(),"Incandescent runtime failed: "+t.runtime.failure());
    }
    @GameCase public void actualKillsUseFourOrEightMetersExcludeAlliesAndApplyTheSelectedStacks(GameTestHelper h) throws Exception {
        for(boolean strong:List.of(false,true))try(var t=harness(h,true,true)) {
            var nearTarget=cow(t,2,500);var far=cow(t,6,500);var outside=cow(t,8.01,500);var ally=cow(t,2,500);ally.setPos(ally.getX(),ally.getY(),ally.getZ()+2);
            var scoreboard=h.getLevel().getScoreboard();var team=scoreboard.addPlayerTeam("incandescent-"+UUID.randomUUID());
            try {
                scoreboard.addPlayerToTeam(t.owner.getScoreboardName(),team);scoreboard.addPlayerToTeam(ally.getScoreboardName(),team);
                kill(t,strong?"secondary":"primary",strong);
                h.assertValueEqual(scorch(t,nearTarget).orElseThrow().count(),strong?60:40,"documented stack branch");
                h.assertValueEqual(scorch(t,far).isPresent(),strong,"4 / 8 meter selection");
                h.assertTrue(scorch(t,outside).isEmpty()&&scorch(t,ally).isEmpty(),"outside or allied target received Scorch");
                near(h,nearTarget.getHealth(),497.6,"native 30 x synthetic falloff and units");near(h,far.getHealth(),strong?498.8:500,"strong burst reaches six meters");
                near(h,outside.getHealth(),500,"outside radius");near(h,ally.getHealth(),500,"allied target");
                h.assertTrue(hits(t,"incandescent").stream().allMatch(d->d.source().weapon().equals(strong?"b":"a")),"other weapon's perk fired");
            }finally{scoreboard.removePlayerTeam(team);}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:incandescent_cycle",maxTicks=40)
    public void twoPhysicalWeaponKillsIgniteWithFirstSourceAndFurtherBurstStillDamagesDuringLockout(GameTestHelper h) throws Exception {
        var t=harness(h,true,true);
        try {
            var survivor=cow(t,2,500);kill(t,"primary",false);var origin=scorch(t,survivor).orElseThrow().origin();
            kill(t,"secondary",true);h.assertTrue(scorch(t,survivor).isEmpty(),"40 plus 60 must consume shared Scorch");
            var ignition=hits(t,"ignition");h.assertValueEqual(ignition.size(),1,"one real ignition");
            h.assertValueEqual(ignition.getFirst().source(),origin,"later weapon stole ignition credit");
            h.assertValueEqual(survivor.getLastDamageSource().getEntity(),t.owner,"native attacker");near(h,survivor.getHealth(),427.6,"two bursts plus ignition");
            t.finish(4,()-> {try {
                kill(t,"primary",false);near(h,survivor.getHealth(),425.2,"lockout does not block burst damage");
                h.assertTrue(scorch(t,survivor).isEmpty(),"lockout failed to reject new Scorch");
                h.assertValueEqual(hits(t,"ignition").size(),1,"lockout allowed repeated ignition");
                h.assertValueEqual(state(t).ammunition().get("a").magazine(),3,"two real weapon costs");
            }catch(Exception e){throw new RuntimeException(e);}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:incandescent_tick",maxTicks=40)
    public void actualScorchRetainsWeaponBonusAndRankExemptionAfterEquipmentIsRemoved(GameTestHelper h) throws Exception {
        var t=harness(h,true,true);
        try {
            bind(t,"bonus","test:incandescent_bonus","b");var survivor=cow(t,2,500);kill(t,"secondary",true);
            near(h,survivor.getHealth(),496.4,"explosion scales with original weapon bonus");
            var equipment=PlayerEquipment.get(t.owner);t.owner.getInventory().setItem(0,ItemStack.EMPTY);equipment.swap(t.owner,"test:secondary",0,equipment.revision());
            t.runtime.unbind("bonus");t.runtime.unbind("solar");
            t.finish(11,()-> {
                near(h,survivor.getHealth(),496.4-(2.7+.175*60)*1.025*.1*1.5,"captured bonus, no combatant rank multiplier");
                var dot=hits(t,"scorch");h.assertValueEqual(dot.size(),1,"one native Scorch tick");h.assertValueEqual(dot.getFirst().source().weapon(),"b","removed weapon attribution");
                h.assertTrue(dot.getFirst().killTags().contains("chorus:weapon_kill"),"Scorch lost weapon kill credit");
            });
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase public void corpseRankChangesMovementAndRemovalCannotChangeConfirmedBlast(GameTestHelper h)throws Exception{
        for(boolean strong:List.of(false,true))for(boolean removed:List.of(false,true))try(var t=harness(h,true,true,EmberOfSearingGameTest::addCorpseCleanup)){
            bind(t,"cleanup","test:corpse_cleanup","");var corpse=cow(t,0,5);var point=corpse.position();if(strong)corpse.addTag("chorus_d2:elite");
            var nearTarget=cow(t,2,500);var far=cow(t,6,500);var outside=cow(t,8.01,500);
            t.onCue=cue->{if(cue.cue().equals("test:remove_corpse")){if(strong)corpse.removeTag("chorus_d2:elite");else corpse.addTag("chorus_d2:boss");corpse.setPos(corpse.getX()+20,corpse.getY(),corpse.getZ());if(removed)corpse.discard();}};
            PugilistGameTest.draw(t,strong?"secondary":"primary");PugilistGameTest.impact(t,PugilistGameTest.fire(t),corpse);
            h.assertValueEqual(scorch(t,nearTarget).orElseThrow().count(),strong?60:40,"original class selects documented layers");h.assertValueEqual(scorch(t,far).isPresent(),strong,"original four/eight meter radius");
            h.assertTrue(scorch(t,outside).isEmpty(),"outside original burst radius");near(h,nearTarget.getHealth(),497.6,"damage uses original center distance");near(h,far.getHealth(),strong?498.8:500,"strong radius and distance retained");
            var center=((com.imdomestic.chorus.effect.target.TargetQuery.PositionCenter)t.queries.getFirst().center()).position().orElseThrow();near(h,center.x(),point.x,"historical center x");near(h,center.y(),point.y,"historical center y");near(h,center.z(),point.z,"historical center z");
            h.assertValueEqual(t.cues.stream().map(com.imdomestic.chorus.effect.data.Action.CueCommand::cue).toList(),List.of("test:remove_corpse"),"history remains available");h.assertTrue(t.runtime.failure().isEmpty(),"historical blast failed");
        }h.succeed();
    }
    @GameCase public void derivedLethalBlastUsesSecondReceiptCenterAfterBothCorpsesAreRemoved(GameTestHelper h)throws Exception{
        try(var t=harness(h,false,true,EmberOfSearingGameTest::addCorpseCleanup)){
            bind(t,"cleanup","test:corpse_cleanup","");var corpse=cow(t,0,5);var second=cow(t,2,1);var far=cow(t,5,500);double x=corpse.getX();
            t.onCue=cue->{if(cue.cue().equals("test:remove_corpse"))for(var dead:List.of(corpse,second))if(dead.isDeadOrDying()&&!dead.isRemoved()){dead.setPos(dead.getX()+20,dead.getY(),dead.getZ());dead.discard();}};
            PugilistGameTest.impact(t,PugilistGameTest.fire(t),corpse);
            h.assertTrue(corpse.isRemoved()&&second.isRemoved(),"each death was cleaned before its kill reaction");h.assertValueEqual(scorch(t,far).orElseThrow().count(),30,"second burst reaches beyond first radius");near(h,far.getHealth(),497.9,"distance three from second death center");
            h.assertValueEqual(t.queries.size(),2,"two receipt-derived blasts without a chain suppression");
            for(int i=0;i<2;i++){var point=((com.imdomestic.chorus.effect.target.TargetQuery.PositionCenter)t.queries.get(i).center()).position().orElseThrow();near(h,point.x(),x+i*2,"each death has its own center");}
            h.assertTrue(hits(t,"incandescent").stream().allMatch(d->d.source().weapon().equals("a")&&d.proc().deny().isEmpty()),"derived credit or proc policy changed");h.assertTrue(t.runtime.failure().isEmpty(),"derived blast failed");
        }h.succeed();
    }
    @GameCase public void actualUncreditedKillAndPerkRemovedInFlightDoNotExplode(GameTestHelper h) throws Exception {
        for(boolean remove:List.of(false,true))try(var t=harness(h,false,remove)) {
            var corpse=cow(t,0,5);var survivor=cow(t,2,500);var shot=PugilistGameTest.fire(t);
            if(remove){var equipment=PlayerEquipment.get(t.owner);t.owner.getInventory().setItem(0,ItemStack.EMPTY);equipment.swap(t.owner,"test:primary",0,equipment.revision());}
            PugilistGameTest.impact(t,shot,corpse);
            h.assertTrue(hits(t,"incandescent").isEmpty()&&scorch(t,survivor).isEmpty(),"unqualified kill triggered perk");near(h,survivor.getHealth(),500,"no invented explosion");
            h.assertValueEqual(state(t).ammunition().get("a").magazine(),4,"paid physical shot retained");
        }h.succeed();
    }
}
