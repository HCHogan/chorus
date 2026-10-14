package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

public class WeaponAimGameTest {
    static ProjectileGameTest.Harness openAim(GameTestHelper h)throws Exception {
        var t=new ProjectileGameTest.Harness(h,"weapon_handling",data->{
            RadiantGameTest.merge(data,"weapon_aim");
            data.getAsJsonArray("weapons").get(0).getAsJsonObject().add("aiming",ThreadedSpikeGameTest.json("weapon_aim_test_calibration"));
        },true);
        try {
            for(String weapon:List.of("a","b")) {
                var stack=new ItemStack(Items.DIAMOND_SWORD);stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear(weapon,"test:rifle",Map.of("perk","kill_clip")));
                t.owner.getInventory().setItem(0,stack);var equipment=PlayerEquipment.get(t.owner);equipment.swap(t.owner,weapon.equals("a")?"test:primary":"test:secondary",0,equipment.revision());
            }
            bind(t,"test:aim_observer",true);draw(t,"primary");return t;
        }catch(Exception|Error error){t.close();throw error;}
    }
    static WeaponAim.Plan aim(ProjectileGameTest.Harness t){return state(t).aiming().get(holder(t));}
    static void input(ProjectileGameTest.Harness t,boolean pressed)throws Exception {
        t.h.assertValueEqual(t.h.getLevel().getServer().getCommands().getDispatcher().execute("chorus weapon aim "+pressed,
                t.owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)),1,"ordinary self-owned aim command");
    }
    static long readyCues(ProjectileGameTest.Harness t){return t.cues.stream().filter(c->c.cue().equals("test:aim_ready")).count();}

    @GameCase(environment="chorus_gametest:weapon_aim_input",maxTicks=35)
    public void ordinaryInputSeparatesAimReadinessAndActualShotKeepsItAfterRelease(GameTestHelper h)throws Exception {
        var t=openAim(h);at(t,6,()->{
            settled(t);bind(t,"test:aim_bonus",true);input(t,true);var accepted=aim(t);long revision=PlayerEquipment.get(t.owner).revision();
            h.assertValueEqual(accepted.phase(),WeaponAim.Phase.ENTERING,"entry phase");h.assertTrue(accepted.held(),"held during entry");
            at(t,3,()->{settled(t);h.assertValueEqual(readyCues(t),0L,"ready too early");input(t,true);h.assertValueEqual(aim(t),accepted,"repeat input restarted time");});
            at(t,4,()->{
                settled(t);h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.AIMED,"ready boundary");h.assertValueEqual(readyCues(t),1L,"ready fact count");
                var victim=t.cow(2.5,46,3.5);var shot=PugilistGameTest.fire(t);input(t,false);h.assertTrue(!aim(t).held(),"release retained held state");
                for(int i=0;i<20&&!shot.isRemoved();i++)shot.tick();
                h.assertTrue(shot.isRemoved()&&!t.receipts.isEmpty(),"actual aimed shot never hit");
                HealingGameTest.near(h,t.receipts.getLast().outgoing().orElseThrow().output().value(),20,"aim captured before release");
                HealingGameTest.near(h,victim.getHealth(),80,"actual damage");
                at(t,2,()->{try(t){settled(t);h.assertTrue(aim(t)==null,"exit retained plan");agrees(t);h.assertValueEqual(PlayerEquipment.get(t.owner).revision(),revision,"aim changed physical selection");h.succeed();}});
            });
        });
    }
    @GameCase(environment="chorus_gametest:weapon_aim_cancel",maxTicks=45)
    public void AcceptedReloadAndSwitchCancelAimButFullReloadDoesNot(GameTestHelper h)throws Exception {
        var t=openAim(h);at(t,6,()->{
            settled(t);input(t,true);h.assertValueEqual(t.runtime.reload(t.owner).outcome(),WeaponReload.Outcome.ACCEPTED,"reload accepted");h.assertTrue(aim(t)==null,"reload did not cancel aim");
            h.assertValueEqual(t.runtime.aim(t.owner,true).outcome(),WeaponAim.Outcome.BUSY,"aim during reload");
            at(t,4,()->{
                settled(t);input(t,true);var accepted=aim(t);h.assertValueEqual(t.runtime.reload(t.owner).outcome(),WeaponReload.Outcome.FULL,"full reload rejected");h.assertValueEqual(aim(t),accepted,"rejected reload cancelled aim");
                draw(t,"secondary");h.assertTrue(aim(t)==null,"accepted switch retained aim");h.assertValueEqual(t.runtime.aim(t.owner,true).outcome(),WeaponAim.Outcome.BUSY,"aim during stow");
                at(t,10,()->{try(t){settled(t);h.assertTrue(aim(t)==null,"old deadline revived aim");h.assertValueEqual(readyCues(t),0L,"cancelled aim claimed ready");h.assertValueEqual(drawn(t),Optional.of("test:secondary"),"physical switch");h.succeed();}});
            });
        });
    }
    @GameCase(environment="chorus_gametest:weapon_aim_reverse",maxTicks=35)
    public void TimingChangesAffectNewEdgesAndCancelledDeadlinesCannotFinishTheirReplacement(GameTestHelper h)throws Exception {
        var t=openAim(h);at(t,6,()->{
            settled(t);bind(t,"test:aim_fast",true);input(t,true);var first=aim(t);h.assertValueEqual(first.duration().value(),.1,"tagged aim timing");bind(t,"test:aim_fast",false);
            at(t,1,()->{input(t,false);var released=aim(t);h.assertValueEqual(released.duration().value(),.1,"exit sampled current modifiers");
                at(t,1,()->{input(t,true);var finalEntry=aim(t);h.assertValueEqual(finalEntry.duration().value(),.2,"new entry timing");
                    t.runtime.start(new RuleEngine.Signal(WeaponAim.DUE,first));t.runtime.start(new RuleEngine.Signal(WeaponAim.DUE,released));h.assertValueEqual(aim(t),finalEntry,"stale deadline changed aim");
                    at(t,3,()->{settled(t);h.assertValueEqual(readyCues(t),0L,"old deadline claimed ready");});
                    at(t,4,()->{try(t){settled(t);h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.AIMED,"replacement ready");h.assertValueEqual(readyCues(t),1L,"duplicate completion");h.succeed();}});
                });
            });
        });
    }
    @GameCase(environment="chorus_gametest:weapon_aim_remove",maxTicks=30)
    public void RemovingTheHeldWeaponCancelsAimEvenIfTheSameItemIsPutBack(GameTestHelper h)throws Exception {
        var t=openAim(h);at(t,6,()->{
            settled(t);input(t,true);var old=aim(t);var equipment=PlayerEquipment.get(t.owner);equipment.swap(t.owner,"test:primary",0,equipment.revision());h.assertTrue(aim(t)==null,"removal retained aim");
            equipment.swap(t.owner,"test:primary",0,equipment.revision());t.runtime.start(new RuleEngine.Signal(WeaponAim.DUE,old));
            at(t,6,()->{try(t){settled(t);h.assertTrue(aim(t)==null,"removed aim revived");h.assertValueEqual(readyCues(t),0L,"removed aim ready fact");h.succeed();}});
        });
    }
    @GameCase(environment="chorus_gametest:weapon_aim_spectator",maxTicks=30)
    public void BecomingSpectatorClearsCompletedAimAndCannotStartAnother(GameTestHelper h)throws Exception {
        var t=openAim(h);at(t,6,()->{input(t,true);at(t,4,()->{try(t){
            settled(t);h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.AIMED,"precondition aimed");t.owner.setGameMode(GameType.SPECTATOR);settled(t);h.assertTrue(aim(t)==null,"spectator retained completed aim");
            boolean rejected=false;try{input(t,true);}catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected){rejected=true;}
            h.assertTrue(rejected,"spectator input accepted");h.succeed();
        }});});
    }
    static void restoreAim(ProjectileGameTest.Harness t,boolean exiting){
        var h=t.h;var saved=t.runtime.checkpoint();var stored=new MinecraftEffectRuntime.Checkpoint(saved.dimension(),saved.gameTime(),EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(saved.effects())),saved.equipmentOwners(), saved.actorOwners());
        var accepted=aim(t);int count=t.cues.size();long revision=PlayerEquipment.get(t.owner).revision();var program=t.runtime.program();t.runtime.close();
        var resumed=MinecraftEffectRuntime.restore(h.getLevel(),program,stored,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),t.world,MinecraftEffectRuntime::nativeSource);
        try {
            h.assertValueEqual(resumed.state().engine().domain().aiming().get(holder(t)),accepted,"restored aim changed");h.assertValueEqual(t.cues.size(),count,"restore replayed facts");
            h.runAfterDelay(1,()->{try(resumed;t){
                resumed.prepare();h.assertTrue(resumed.failure().isEmpty(),"restored aim failed");var actual=resumed.state().engine().domain().aiming().get(holder(t));
                h.assertTrue(exiting?actual==null:actual!=null&&actual.phase()==WeaponAim.Phase.AIMED,"original deadline not respected");
                h.assertValueEqual(PlayerEquipment.get(t.owner).revision(),revision,"restore changed selection");h.succeed();
            }});
        }catch(Exception|Error error){resumed.close();t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:weapon_aim_restore_enter",maxTicks=30)
    public void EnteringCheckpointCompletesAgainstTheOwnedContainerOnItsFirstResumedTick(GameTestHelper h)throws Exception {
        var t=openAim(h);at(t,6,()->{input(t,true);at(t,3,()->restoreAim(t,false));});
    }
    @GameCase(environment="chorus_gametest:weapon_aim_restore_exit",maxTicks=30)
    public void ExitingCheckpointEndsOnItsOriginalDeadlineWithoutReplayingRelease(GameTestHelper h)throws Exception {
        var t=openAim(h);at(t,6,()->{input(t,true);at(t,4,()->{input(t,false);at(t,1,()->restoreAim(t,true));});});
    }
    @GameCase(environment="chorus_gametest:weapon_aim_unknown",maxTicks=30)
    public void UnknownReadyReactionKeepsCompletedAimWithoutReplayingItsFact(GameTestHelper h)throws Exception {
        var t=openAim(h);at(t,6,()->{
            input(t,true);t.failAfterCue=true;
            h.runAfterDelay(5,()->{try(t){
                h.assertTrue(t.runtime.failure().isPresent(),"injected cue did not fail");h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.AIMED,"committed aim rolled back");
                long count=readyCues(t);t.runtime.prepare();h.assertValueEqual(readyCues(t),count,"unknown cue replayed");h.assertValueEqual(count,1L,"ready count");h.succeed();
            }});
        });
    }
}
