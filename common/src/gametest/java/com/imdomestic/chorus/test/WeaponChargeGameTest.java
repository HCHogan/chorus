package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.ExplosivePayloadGameTest.*;
import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Release-triggered physical arrows; 0.1/0.4 seconds and the perfect-draw bonus are synthetic test data. */
public class WeaponChargeGameTest {
    static ProjectileGameTest.Harness openCharge(GameTestHelper h)throws Exception{
        var t=ExplosiveHeadGameTest.openHead(h,data->{
            RadiantGameTest.merge(data,"weapon_charge");var weapon=data.getAsJsonArray("weapons").get(0).getAsJsonObject();weapon.add("charging",ThreadedSpikeGameTest.json("weapon_charge_test_calibration"));
            for(var step:weapon.getAsJsonObject("fire").getAsJsonArray("on_fire")){
                var action=step.getAsJsonObject().getAsJsonObject("action");if(action==null||!action.get("type").getAsString().equals("chorus:capture_damage"))continue;
                var value=new JsonObject();value.addProperty("type","chorus:arithmetic");value.addProperty("op","mul");var operands=new JsonArray();operands.add(action.get("amount"));operands.add(ThreadedSpikeGameTest.json("weapon_charge_damage_test_calibration"));value.add("of",operands);action.add("amount",value);
            }
        });
        try{IncandescentGameTest.bind(t,"charge-observer","test:charge_observer","a");return t;}catch(Exception|Error error){t.close();throw error;}
    }
    static WeaponCharge.Plan charge(ProjectileGameTest.Harness t){return state(t).charging().get(holder(t));}
    static int ammo(ProjectileGameTest.Harness t){return state(t).ammunition().get("a").magazine();}
    static void input(ProjectileGameTest.Harness t,String edge)throws Exception{
        t.h.assertValueEqual(t.h.getLevel().getServer().getCommands().getDispatcher().execute("chorus weapon charge "+edge,t.owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)),1,"ordinary player's paired charge input");
    }
    @GameCase(environment="chorus_gametest:charge_partial",maxTicks=30)
    public void partialReleasePaysOnceAndItsPhysicalArrowKeepsBothDamageComponents(GameTestHelper h)throws Exception{
        var t=openCharge(h);try{
            input(t,"press");h.assertValueEqual(ammo(t),10,"press does not pay");h.assertTrue(t.projectiles.isEmpty(),"press launched an arrow");
            at(t,4,()->{input(t,"release");h.assertValueEqual(ammo(t),9,"one release payment");h.assertTrue(charge(t)==null,"released charge retained");var shot=t.projectiles.getLast();var target=primary(t,shot,5);contact(t,shot);near(h,target.getHealth(),97.5,"half-draw direct body");
                at(t,4,()->{try(t){settled(t);near(h,target.getHealth(),94.25,"half-draw delayed explosion");h.assertValueEqual(t.runtime.charge(t.owner,WeaponCharge.Edge.RELEASE).outcome(),WeaponCharge.Outcome.NO_PRESS,"repeat release");h.assertValueEqual(t.projectiles.size(),1,"duplicate arrow");h.succeed();}});
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:charge_full",maxTicks=35)
    public void fullChargeWaitsForReleaseAndContentReadsThePerfectDrawWindow(GameTestHelper h)throws Exception{
        var t=openCharge(h);try{
            input(t,"press");h.assertValueEqual(t.runtime.fire(t.owner).outcome(),WeaponFire.Outcome.CHARGE_REQUIRED,"ordinary fire bypassed charge");
            at(t,8,()->{settled(t);h.assertValueEqual(charge(t).phase(),WeaponCharge.Phase.READY,"full boundary");h.assertTrue(t.projectiles.isEmpty(),"full draw auto-fired");h.assertValueEqual(ammo(t),10,"full draw paid before release");input(t,"release");var shot=t.projectiles.getLast();var target=primary(t,shot,5);region(t,target);contact(t,shot);near(h,target.getHealth(),82,"synthetic perfect precision factor");
                at(t,4,()->{try(t){settled(t);near(h,target.getHealth(),74.2,"nonprecision blast keeps draw factor");h.assertTrue(!t.hits.getLast().tags().contains("chorus:precision"),"blast inherited precision");h.succeed();}});
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:charge_snapshot",maxTicks=30)
    public void acceptedTimingSurvivesBuffRemovalAndStaleReleaseCannotConsumeTheCurrentPress(GameTestHelper h)throws Exception{
        var t=openCharge(h);try{
            IncandescentGameTest.bind(t,"fast","test:charge_fast","a");var accepted=t.runtime.charge(t.owner,WeaponCharge.Edge.PRESS).plan().orElseThrow();h.assertValueEqual(accepted.fullAt()-accepted.startedAt(),200_000L,"accepted fast duration");t.runtime.unbind("fast");
            h.assertValueEqual(t.runtime.charge(t.owner,"wrong",WeaponCharge.Edge.RELEASE).outcome(),WeaponCharge.Outcome.STALE,"wrong gesture released charge");h.assertValueEqual(charge(t),accepted,"stale release changed plan");
            at(t,4,()->{try(t){settled(t);h.assertValueEqual(charge(t).phase(),WeaponCharge.Phase.READY,"buff removal changed accepted deadline");h.assertValueEqual(t.runtime.charge(t.owner,WeaponCharge.Edge.PRESS).outcome(),WeaponCharge.Outcome.ALREADY_HELD,"repeat press reset plan");input(t,"cancel");h.assertTrue(charge(t)==null&&t.projectiles.isEmpty(),"cancel fired or retained charge");h.assertValueEqual(ammo(t),10,"cancel paid");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:charge_restricted",maxTicks=30)
    public void OngoingNativeRestrictionCancelsBothChargingAndReadyPhases(GameTestHelper h)throws Exception{
        var t=openCharge(h);try{
            input(t,"press");IncandescentGameTest.bind(t,"deny","test:charge_denied","a");settled(t);h.assertTrue(charge(t)==null,"ongoing restriction retained charging");h.assertValueEqual(ammo(t),10,"restriction paid");t.runtime.unbind("deny");input(t,"press");
            at(t,8,()->{try(t){settled(t);h.assertValueEqual(charge(t).phase(),WeaponCharge.Phase.READY,"new full draw");IncandescentGameTest.bind(t,"deny","test:charge_denied","a");settled(t);h.assertTrue(charge(t)==null&&t.projectiles.isEmpty(),"ready charge survived action denial");h.assertValueEqual(t.runtime.charge(t.owner,WeaponCharge.Edge.RELEASE).outcome(),WeaponCharge.Outcome.NO_PRESS,"cancelled intent fired");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:charge_lifecycle",maxTicks=30)
    public void physicalUnequipSpectatorAndAcceptedReloadEndTheCharge(GameTestHelper h)throws Exception{
        for(boolean spectator:List.of(false,true))try(var t=openCharge(h)){
            input(t,"press");if(spectator)t.owner.setGameMode(GameType.SPECTATOR);else{var equipment=PlayerEquipment.get(t.owner);t.owner.getInventory().setItem(0,ItemStack.EMPTY);equipment.swap(t.owner,"test:primary",0,equipment.revision());}
            settled(t);h.assertTrue(charge(t)==null&&t.projectiles.isEmpty(),"physical eligibility lost without cancellation");h.assertValueEqual(ammo(t),10,"cancelled charge paid");
        }
        var t=openCharge(h);try{
            input(t,"press");var plan=charge(t);h.assertValueEqual(t.runtime.reload(t.owner).outcome(),WeaponReload.Outcome.FULL,"full reload rejection");h.assertValueEqual(charge(t),plan,"rejected reload cancelled charge");
            at(t,2,()->{input(t,"release");h.assertValueEqual(ammo(t),9,"minimum release accepted");at(t,3,()->{try(t){input(t,"press");h.assertValueEqual(t.runtime.reload(t.owner).outcome(),WeaponReload.Outcome.ACCEPTED,"actual reload after shot");h.assertTrue(charge(t)==null,"accepted reload kept an active charge");h.assertValueEqual(t.runtime.charge(t.owner,WeaponCharge.Edge.PRESS).outcome(),WeaponCharge.Outcome.BUSY,"charge entered during reload");h.succeed();}});});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:charge_life",maxTicks=30)
    public void nativeRespawnWithTheSameUuidAndTransferredGearCannotInheritTheOldCharge(GameTestHelper h)throws Exception{
        try(var t=openCharge(h)){
            input(t,"press");var accepted=charge(t);var oldLife=accepted.holderReference().orElseThrow();var oldEquipment=PlayerEquipment.get(t.owner);var oldStack=oldEquipment.item("test:primary");var oldDrawn=oldEquipment.snapshot().drawn();
            t.owner.setHealth(0);var replacement=h.getLevel().getServer().getPlayerList().respawn(t.owner,false,Entity.RemovalReason.KILLED);t.entities.add(replacement);
            h.assertValueEqual(replacement.getUUID(),t.owner.getUUID(),"native UUID reuse");var nextEquipment=PlayerEquipment.get(replacement);h.assertTrue(ItemStack.isSameItemSameComponents(nextEquipment.item("test:primary"),oldStack),"native equipment transfer");h.assertValueEqual(nextEquipment.snapshot().drawn(),oldDrawn,"native drawn-slot transfer");h.assertTrue(!MinecraftEntityLife.get(replacement).id().equals(oldLife.life()),"native life did not rotate");
            settled(t);h.assertTrue(charge(t)==null,"new life inherited old charge");h.assertValueEqual(t.runtime.charge(replacement,accepted.token(),WeaponCharge.Edge.RELEASE).outcome(),WeaponCharge.Outcome.NO_PRESS,"new life released old gesture");h.assertValueEqual(ammo(t),10,"respawn paid old charge");h.assertTrue(t.projectiles.isEmpty(),"respawn fired");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:charge_unknown",maxTicks=30)
    public void unknownPhysicalDamageAfterReleaseKeepsPaymentAndDoesNotReplayTheArrow(GameTestHelper h)throws Exception{
        var t=openCharge(h);try{
            input(t,"press");at(t,4,()->{try(t){input(t,"release");var shot=t.projectiles.getLast();var target=primary(t,shot,5);t.failAfterDamage=true;
                try{for(int i=0;i<35&&!shot.isRemoved();i++)shot.tick();}catch(IllegalStateException expected){}
                h.assertTrue(t.runtime.failure().isPresent(),"unknown damage did not stop runtime");near(h,target.getHealth(),97.5,"applied partial damage remains");h.assertValueEqual(ammo(t),9,"accepted release payment remains");h.assertTrue(charge(t)==null,"failed shot revived input");int operations=t.operations.size();t.runtime.prepare();h.assertValueEqual(t.operations.size(),operations,"failed arrow replayed");h.assertValueEqual(t.projectiles.size(),1,"duplicate physical arrow");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
}
