package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.WeaponChargeGameTest.*;
import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.ExplosivePayloadGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.network.WeaponChargePayloads.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.weapon.WeaponCharge;
import com.imdomestic.chorus.network.WeaponChargeNetworkServer;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.ServerLevelData;

/** Synthetic timing and bow consumer exercise generic full-trigger input and native world writes. */
public class WeaponAutoChargeGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h,double seconds)throws Exception{
        var t=ExplosiveHeadGameTest.openHead(h,data->{
            RadiantGameTest.merge(data,"weapon_charge");var charge=ThreadedSpikeGameTest.json("weapon_charge_test_calibration");charge.addProperty("trigger","full");charge.getAsJsonObject("full").getAsJsonObject("value").addProperty("value",seconds);
            data.getAsJsonArray("weapons").get(0).getAsJsonObject().add("charging",charge);
        });
        try{IncandescentGameTest.bind(t,"auto-observer","test:charge_observer","a");return t;}catch(Exception|Error error){t.close();throw error;}
    }
    /** No periodic network maintenance: the runtime must verify the lease even if it advances first. */
    static final class Wire implements AutoCloseable {
        final ProjectileGameTest.Harness t;final List<View> views=new ArrayList<>();final WeaponChargeNetworkServer net=new WeaponChargeNetworkServer((_,v)->views.add(v));long sequence,gesture;boolean closed;
        Wire(ProjectileGameTest.Harness t){this.t=t;}
        Input packet(long sequence,long gesture,Edge edge){return new Input(sequence,gesture,t.h.getLevel().dimension().identifier().toString(),edge);}
        WeaponChargeNetworkServer.Reply edge(Edge edge){++sequence;if(edge==Edge.PRESS)gesture=sequence;return net.request(t.owner,packet(sequence,gesture,edge));}
        View view(){net.tick(t.h.getLevel());return views.getLast();}
        @Override public void close(){if(!closed){closed=true;net.disconnected(t.owner.connection);t.close();}}
    }
    static void future(Wire n,int ticks,Runnable run){
        long original=n.t.h.getLevel().getGameTime();var data=(ServerLevelData)n.t.h.getLevel().getLevelData();
        try{data.setGameTime(original+ticks);run.run();}finally{try{n.close();}finally{data.setGameTime(original);}}
    }
    static ServerPlayer second(ProjectileGameTest.Harness t){
        var player=NativeMeleeGameTest.player(t.h);t.entities.add(player);player.setGameMode(GameType.SURVIVAL);
        var stack=new ItemStack(Items.BOW);stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear("other-bow","test:bow",Map.of("perk","normal")));player.getInventory().setItem(0,stack);
        var e=PlayerEquipment.get(player);e.swap(player,"test:primary",0,e.revision());e.draw(player,Optional.of("test:primary"),e.revision());return player;
    }
    @GameCase(environment="chorus_gametest:auto_charge_fire",maxTicks=40)
    public void nativeFullBoundaryFiresWithoutReleaseAndReportsOneAcceptedShot(GameTestHelper h)throws Exception{
        var t=open(h,.4);var n=new Wire(t);n.edge(Edge.PRESS);
        at(t,7,()->{settled(t);h.assertTrue(t.projectiles.isEmpty(),"automatic shot before full boundary");h.assertValueEqual(ammo(t),10,"early payment");});
        at(t,8,()->{settled(t);h.assertTrue(charge(t)==null,"automatic shot left an active plan");h.assertValueEqual(ammo(t),9,"one automatic payment");h.assertValueEqual(n.view().status(),Status.FIRED,"automatic fire acknowledgement");
            var shot=t.projectiles.getLast();var target=primary(t,shot,5);contact(t,shot);near(h,target.getHealth(),95,"native direct damage");
            at(t,4,()->{settled(t);near(h,target.getHealth(),88.5,"delayed damage continues after consumed input");});
            at(t,17,()->{try(n){settled(t);h.assertValueEqual(t.projectiles.size(),1,"held gesture repeated the shot");h.assertValueEqual(n.edge(Edge.HOLD),WeaponChargeNetworkServer.Reply.STALE,"heartbeat rearmed completed charge");h.assertValueEqual(n.edge(Edge.RELEASE),WeaponChargeNetworkServer.Reply.STALE,"release fired twice");h.assertValueEqual(ammo(t),9,"repeat payment");h.succeed();}});
        });
    }
    @GameCase(environment="chorus_gametest:auto_charge_release",maxTicks=30)
    public void releaseAboveMinimumStillCancelsAndRecoveryRequiresANewPress(GameTestHelper h)throws Exception{
        var t=open(h,.4);var n=new Wire(t);n.edge(Edge.PRESS);
        at(t,4,()->{n.edge(Edge.RELEASE);h.assertTrue(charge(t)==null&&t.projectiles.isEmpty(),"early automatic trigger became a partial shot");h.assertValueEqual(n.view().status(),Status.CANCELLED,"early full-trigger release acknowledgement");
            at(t,5,()->{h.assertTrue(t.projectiles.isEmpty(),"cancelled full timer fired");h.assertValueEqual(n.edge(Edge.HOLD),WeaponChargeNetworkServer.Reply.STALE,"cancelled hold restarted");n.edge(Edge.PRESS);
                at(t,8,()->{try(n){settled(t);h.assertValueEqual(n.view().status(),Status.FIRED,"fresh press failed");h.assertValueEqual(ammo(t),9,"only fresh press pays");h.succeed();}});
            });
        });
    }
    @GameCase(environment="chorus_gametest:auto_charge_lease",maxTicks=25)
    public void expiredLeaseRejectsFullTimersEvenWhenRuntimeAdvancesBeforeNetworkMaintenance(GameTestHelper h)throws Exception{
        for(double seconds:new double[]{.4,2}){
            var t=open(h,seconds);var n=new Wire(t);n.edge(Edge.PRESS);
            future(n,40,()->{t.runtime.prepare();h.assertTrue(t.runtime.failure().isEmpty(),"lease denial became runtime failure");h.assertTrue(charge(t)==null&&t.projectiles.isEmpty(),"expired input fired during catch-up");h.assertValueEqual(ammo(t),10,"expired input paid");h.assertValueEqual(n.edge(Edge.HOLD),WeaponChargeNetworkServer.Reply.STALE,"late heartbeat revived input");});
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:auto_charge_heartbeat",maxTicks=55)
    public void validHeartbeatAuthorizesTheAcceptedFullDeadlineWithoutRestartingIt(GameTestHelper h)throws Exception{
        var t=open(h,2);var n=new Wire(t);n.edge(Edge.PRESS);long full=charge(t).fullAt();
        at(t,30,()->{n.edge(Edge.HOLD);h.assertValueEqual(charge(t).fullAt(),full,"heartbeat restarted duration");});
        at(t,40,()->{try(n){settled(t);h.assertValueEqual(n.view().status(),Status.FIRED,"live lease rejected full shot");h.assertValueEqual(ammo(t),9,"one full charge payment");h.assertValueEqual(t.projectiles.size(),1,"one native shot");h.succeed();}});
    }
    @GameCase(environment="chorus_gametest:auto_charge_disconnect",maxTicks=25)
    public void disconnectRevokesNetworkAndCommandAuthorityBeforeSettlingOverdueTimers(GameTestHelper h)throws Exception{
        for(boolean command:List.of(false,true)){
            var t=open(h,.4);var n=new Wire(t);if(command)t.runtime.charge(t.owner,WeaponCharge.Edge.PRESS);else n.edge(Edge.PRESS);
            future(n,8,()->{n.net.disconnected(t.owner.connection);h.assertTrue(charge(t)==null&&t.projectiles.isEmpty(),"disconnect fired a due plan");h.assertValueEqual(ammo(t),10,"disconnect paid");});
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:auto_charge_quiesce",maxTicks=25)
    public void quiescenceRevokesEveryHolderBeforeAnyRuntimeCanAutofire(GameTestHelper h)throws Exception{
        for(int mode=0;mode<3;mode++){
            var t=open(h,.4);var n=new Wire(t);var other=second(t);
            if(mode==1){t.runtime.charge(t.owner,WeaponCharge.Edge.PRESS);t.runtime.charge(other,WeaponCharge.Edge.PRESS);}
            else{n.edge(Edge.PRESS);if(mode==2)t.runtime.charge(other,WeaponCharge.Edge.PRESS);else n.net.request(other,n.packet(1,1,Edge.PRESS));}
            boolean allInputs=mode!=0;
            future(n,8,()->{if(allInputs)MinecraftEffectRuntime.quiesceCharges(h.getLevel().getServer());n.net.quiesce(h.getLevel().getServer());
                h.assertTrue(state(t).charging().isEmpty()&&t.projectiles.isEmpty(),"quiescing first holder fired another holder");h.assertValueEqual(ammo(t),10,"owner paid during stop");h.assertValueEqual(state(t).ammunition().get("other-bow").magazine(),10,"other holder paid during stop");});
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:auto_charge_restore",maxTicks=25)
    public void restoredDomainPlansHaveNoLiveInputAuthorityAtTheirFirstClockAdvance(GameTestHelper h)throws Exception{
        var t=open(h,.4);var n=new Wire(t);n.edge(Edge.PRESS);var saved=t.runtime.checkpoint();var program=t.runtime.program();t.runtime.close();
        try(var restored=MinecraftEffectRuntime.restore(h.getLevel(),program,saved,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),t.world,MinecraftEffectRuntime::nativeSource)){
            future(n,8,()->{restored.prepare();h.assertTrue(restored.failure().isEmpty(),"missing input authority faulted restored runtime");var state=restored.state().engine().domain();h.assertTrue(state.charging().isEmpty()&&t.projectiles.isEmpty(),"saved plan reconstructed a physical hold");h.assertValueEqual(state.ammunition().get("a").magazine(),10,"restore fired");restored.close();});
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:auto_charge_unknown",maxTicks=25)
    public void unknownAutomaticReactionKeepsPaymentAndCannotReplayFromHeldOrReleasePackets(GameTestHelper h)throws Exception{
        var t=open(h,.4);var n=new Wire(t);n.edge(Edge.PRESS);t.failAfterCue=true;
        future(n,8,()->{t.runtime.prepare();h.assertTrue(t.runtime.failure().isPresent()&&charge(t)==null,"unknown full-fire reaction revived plan");h.assertValueEqual(ammo(t),9,"committed full-fire payment lost");int operations=t.operations.size();h.assertValueEqual(n.view().status(),Status.FAILED,"failed automatic projection");n.edge(Edge.HOLD);n.edge(Edge.RELEASE);t.runtime.prepare();h.assertValueEqual(t.operations.size(),operations,"unknown automatic work replayed");});h.succeed();
    }
    @GameCase(environment="chorus_gametest:auto_charge_stale_cancel",maxTicks=25)
    public void staleCancellationCannotRevokeANewerCommandsAutomaticAuthority(GameTestHelper h)throws Exception{
        var t=open(h,.4);var n=new Wire(t);n.edge(Edge.PRESS);var old=charge(t);t.runtime.cancelCharge(holder(t),old.token(),"replace");t.runtime.charge(t.owner,WeaponCharge.Edge.PRESS);var next=charge(t);
        future(n,8,()->{t.runtime.cancelCharge(holder(t),old.token(),"stale");h.assertTrue(charge(t)==null,"new full charge failed to complete");h.assertValueEqual(ammo(t),9,"stale token revoked new authority");h.assertValueEqual(state(t).shots().get("a").token(),next.token(),"old token claimed new shot");h.assertValueEqual(n.view().status(),Status.CANCELLED,"old network gesture claimed command shot");h.assertValueEqual(t.projectiles.size(),1,"new command did not launch");});h.succeed();
    }
}
