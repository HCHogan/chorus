package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.Action;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

/** Physical weapon deaths -> Tempering -> private world pickup -> current grenade account. */
public class FirespriteGameTest {
    static final String ENERGY="chorus_d2:arcbolt_energy", BUFF="chorus_d2:tempering", COOLDOWN="chorus_d2:firesprite_cooldown";
    static void prepare(JsonObject data) {
        for(String fixture:List.of("firesprite_test_calibration","ember_of_tempering","character_stats","weapon_stats","arcbolt_energy","tempering_weapon")) {
            var fragment=ThreadedSpikeGameTest.json(fixture);
            for(var entry:fragment.entrySet()) {
                if(entry.getKey().equals("version"))continue;
                if(entry.getValue().isJsonArray()) {
                    if(!data.has(entry.getKey()))data.add(entry.getKey(),new JsonArray());
                    entry.getValue().getAsJsonArray().forEach(e->data.getAsJsonArray(entry.getKey()).add(e));
                }else data.add(entry.getKey(),entry.getValue());
            }
        }
    }
    static ProjectileGameTest.Harness harness(GameTestHelper h) throws Exception {
        var t=new ProjectileGameTest.Harness(h,"firesprite",FirespriteGameTest::prepare,true);
        t.owner.setPos(t.owner.chunkPosition().getMinBlockX()+6.5,t.owner.getY(),t.owner.chunkPosition().getMinBlockZ()+6.5);
        t.owner.setGameMode(GameType.SURVIVAL);var equipment=PlayerEquipment.get(t.owner);
        for(String id:List.of("a","b")){
            var stack=new ItemStack(Items.DIAMOND_SWORD);stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear(id,"test:rifle",Map.of("perk","none")));
            t.owner.getInventory().setItem(0,stack);equipment.swap(t.owner,id.equals("a")?"test:primary":"test:secondary",0,equipment.revision());
        }
        IncandescentGameTest.bind(t,"tempering","chorus_d2:ember_of_tempering","");
        IncandescentGameTest.bind(t,"firesprite","chorus_d2:firesprite_system","");
        IncandescentGameTest.bind(t,"inputs","test:tempering_inputs","");
        DemolitionistGameTest.select(t,"grenade");DemolitionistGameTest.use(t);return t;
    }
    static EffectState state(ProjectileGameTest.Harness t){return t.runtime.state().engine().domain();}
    static String id(LivingEntity e){return e.getUUID().toString();}
    static double energy(ProjectileGameTest.Harness t){return state(t).resources().get(new ResourceState.Key(id(t.owner),ENERGY)).value();}
    static Optional<BuffInstance> buff(ProjectileGameTest.Harness t,LivingEntity e,String id){return state(t).buffs().instances().values().stream().filter(b->b.key().holder().equals(id(e))&&b.definition().id().equals(id)).findFirst();}
    static double stat(ProjectileGameTest.Harness t,LivingEntity e,String profile){return t.runtime.program().calculate(state(t),id(e),new EffectEvent(id(e),id(e),new BuffInstance.Origin(id(e),"query","",""),Set.of(),Map.of()),"chorus_d2:"+profile,new Measure(50,Unit.STAT_POINT),List.of()).output().value();}
    static LivingEntity cow(ProjectileGameTest.Harness t,double x,double y){var c=t.cow(2.5,46,3.5);c.setPos(t.owner.position().add(x,y,0));return c;}
    static void kill(ProjectileGameTest.Harness t,String slot) throws Exception {
        PugilistGameTest.draw(t,slot);var victim=cow(t,0,6);victim.setHealth(1);PugilistGameTest.impact(t,PugilistGameTest.fire(t),victim);
    }
    static void pair(ProjectileGameTest.Harness t) throws Exception {
        kill(t,"primary");t.h.assertTrue(t.pickups.isEmpty(),"activation kill must not spawn");kill(t,"secondary");
        t.h.assertValueEqual(t.pickups.size(),1,"second native kill creates private Firesprite");
        t.h.assertTrue(t.hits.stream().allMatch(d->d.source().tags().contains("chorus_d2:solar")&&d.killTags().contains("chorus:weapon_kill")),"native kill attribution lost element or weapon credit");
    }
    @GameCase public void nativeKillsBuffNearbyAlliesThenPrivateCollectionUsesLiveGrenadeAfterUnequip(GameTestHelper h) throws Exception {
        try(var t=harness(h)){
            var ally=cow(t,3,0);var edge=cow(t,0,-15);var outside=cow(t,0,-15.01);var enemy=cow(t,4,0);
            var scoreboard=h.getLevel().getScoreboard();var team=scoreboard.addPlayerTeam("tempering-"+UUID.randomUUID());
            try {
                for(var e:List.of(t.owner,ally,edge,outside))scoreboard.addPlayerToTeam(e.getScoreboardName(),team);
                pair(t);h.assertValueEqual(buff(t,ally,BUFF).orElseThrow().count(),2,"allied stacks");
                h.assertTrue(buff(t,edge,BUFF).isPresent()&&buff(t,outside,BUFF).isEmpty()&&buff(t,enemy,BUFF).isEmpty(),"15-meter allied selection");
                near(h,stat(t,ally,"health_stat"),90,"two-stack Health query");near(h,stat(t,ally,"weapon_airborne_effectiveness"),70,"flat AE query");near(h,stat(t,t.owner,"class_stat"),40,"fragment penalty");
                var pickup=t.pickups.getFirst();var point=pickup.position();enemy.setPos(point);ally.setPos(point);pickup.tick();
                h.assertTrue(!pickup.isRemoved()&&t.cues.isEmpty(),"others stole the private unit");
                DemolitionistGameTest.input(t,"stat",id(t.owner),"stat",100,Unit.STAT_POINT);
                t.runtime.unbind("tempering");t.runtime.unbind("firesprite");double before=energy(t);t.owner.setPos(point);pickup.tick();
                near(h,energy(t)-before,.05*.75*2.25,"explicit test normalization, current stat and recipient scalar once");
                h.assertTrue(pickup.isRemoved(),"collection consumes physical entity");h.assertValueEqual(t.cues.stream().map(Action.CueCommand::target).toList(),List.of(id(t.owner)),"one collector fact");
                pickup.tick();near(h,energy(t)-before,.084375,"no duplicate reward");near(h,stat(t,t.owner,"class_stat"),50,"unequipped fragment penalty removed");
            }finally{scoreboard.removePlayerTeam(team);}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:firesprite_cooldown",maxTicks=125)
    public void physicalSpawnsShareFiveSecondCooldownAcrossBothWeapons(GameTestHelper h) throws Exception {
        var t=harness(h);
        try {
            pair(t);var first=t.pickups.getFirst();long deadline=buff(t,t.owner,COOLDOWN).orElseThrow().deadline();
            h.runAfterDelay(99,()->{try{kill(t,"primary");h.assertValueEqual(t.pickups.size(),1,"still in five-second cooldown");h.assertValueEqual(buff(t,t.owner,COOLDOWN).orElseThrow().deadline(),deadline,"suppressed kill did not reset cooldown");}catch(Exception|Error e){t.close();throw new RuntimeException(e);}});
            t.finish(102,()->{try{kill(t,"secondary");h.assertValueEqual(t.pickups.size(),2,"cooldown expired on real server clock");h.assertTrue(!first.isRemoved(),"old uncollected unit remains alive");}catch(Exception e){throw new RuntimeException(e);}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:firesprite_expiry",maxTicks=530)
    public void originalTwentyFiveSecondLifetimeExpiresWithoutCollectionFact(GameTestHelper h) throws Exception {
        var t=harness(h);
        try {
            pair(t);var pickup=t.pickups.getFirst();h.assertValueEqual(pickup.pickup().orElseThrow().parameters().lifetimeMicros(),25_000_000L,"original lifetime");
            h.runAfterDelay(499,()->{try{h.assertTrue(!pickup.isRemoved(),"expired too early");}catch(Throwable e){t.close();throw e;}});
            t.finish(502,()->{h.assertTrue(pickup.isRemoved()&&t.cues.isEmpty(),"expiry generated a collection fact");h.assertTrue(buff(t,t.owner,BUFF).isEmpty()&&buff(t,t.owner,COOLDOWN).isEmpty(),"expired buffs retained");});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase public void unknownPickupListenerOutcomeNeverReplaysAlreadyCommittedEnergy(GameTestHelper h) throws Exception {
        try(var t=harness(h)){
            pair(t);var pickup=t.pickups.getFirst();double before=energy(t);t.failAfterCue=true;t.owner.setPos(pickup.position());pickup.tick();
            h.assertTrue(t.runtime.failure().isPresent()&&pickup.isRemoved(),"unknown fact outcome must consume unit and retain failure");near(h,energy(t)-before,.0375,"energy committed before failed listener");
            for(int i=0;i<4;i++)pickup.tick();near(h,energy(t)-before,.0375,"no reward replay");h.assertValueEqual(t.cues.size(),1,"no listener replay");
        }h.succeed();
    }
}
