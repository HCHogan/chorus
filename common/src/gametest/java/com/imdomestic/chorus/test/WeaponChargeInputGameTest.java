package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.WeaponChargeGameTest.*;
import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import static com.imdomestic.chorus.network.WeaponChargePayloads.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.weapon.WeaponCharge;
import com.imdomestic.chorus.network.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;

public class WeaponChargeInputGameTest {
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void relocatedChargePayloadPreservesSignedMarkersAndProgressThroughNativeCodec(GameTestHelper h) {
        var player=UUID.randomUUID();var dimension=h.getLevel().dimension().identifier().toString();
        var charging=new View(1,1,1,player,7,dimension,true,Status.ACTIVE,Phase.CHARGING,"carried",-200_000,-100_000,200_000,0);
        var ready=new View(2,1,1,player,7,dimension,true,Status.ACTIVE,Phase.READY,"carried",-500_000,-400_000,-100_000,0);
        var recovering=new View(3,1,1,player,7,dimension,true,Status.ACTIVE,Phase.RECOVERING,"carried",-50_000,-50_000,100_000,0);
        var views=List.of(charging,ready,recovering);
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),h.getLevel().registryAccess());
        try {
            for(var view:views)View.CODEC.encode(buffer,view);
            for(var view:views){var decoded=View.CODEC.decode(buffer);h.assertValueEqual(decoded,view,"signed charge markers changed on wire");HealingGameTest.near(h,decoded.fraction(),view.fraction(),"charge progress changed on wire");}
            h.assertTrue(!buffer.isReadable(),"charge decoder left unread packet fields");
        } finally {buffer.release();}
        HealingGameTest.near(h,charging.fraction(),.5,"relocated half charge lost elapsed progress");
        HealingGameTest.near(h,ready.fraction(),1,"charge completed before destination epoch lost ready progress");
        HealingGameTest.near(h,recovering.fraction(),1./3,"relocated shot recovery lost elapsed progress");
        ResourceProjectionGameTest.refused(h,IllegalArgumentException.class,()->new View(1,1,1,player,7,dimension,true,Status.ACTIVE,Phase.CHARGING,"carried",-200_000,-100_000,200_000,-1));
        ResourceProjectionGameTest.refused(h,ArithmeticException.class,()->new View(1,1,1,player,7,dimension,true,Status.ACTIVE,Phase.CHARGING,"carried",Long.MIN_VALUE,0,1,0));
        h.succeed();
    }
    public static CompiledEffects program() {
        return program(false);
    }
    public static CompiledEffects program(boolean automatic) {
        var data = ThreadedSpikeGameTest.json("explosive_head_weapon");
        RadiantGameTest.merge(data,"explosive_head","explosive_head_test_calibration","explosive_payload","explosive_payload_test_calibration","weapon_stats","weapon_charge");
        var charge=ThreadedSpikeGameTest.json("weapon_charge_test_calibration");
        if(automatic){charge.addProperty("trigger","full");charge.getAsJsonObject("full").getAsJsonObject("value").addProperty("value",2.0);}
        data.getAsJsonArray("weapons").get(0).getAsJsonObject().add("charging",charge);
        return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,data).getOrThrow();
    }
    static final class InputHarness implements AutoCloseable {
        final ProjectileGameTest.Harness t; final List<View> views = new ArrayList<>();
        final WeaponChargeNetworkServer net = new WeaponChargeNetworkServer((_, view) -> views.add(view));
        long sequence, gesture; boolean closed, heartbeat;
        InputHarness(ProjectileGameTest.Harness t) {
            this.t=t;
            t.h.onEachTick(()->{if(!closed){if(heartbeat&&gesture>0&&t.h.getLevel().getGameTime()%10==0)edge(Edge.HOLD);net.tick(t.h.getLevel());}});
        }
        Input packet(long sequence,long gesture,Edge edge){return new Input(sequence,gesture,t.h.getLevel().dimension().identifier().toString(),edge);}
        WeaponChargeNetworkServer.Reply edge(Edge edge){++sequence;if(edge==Edge.PRESS)gesture=sequence;return net.request(t.owner,packet(sequence,gesture,edge));}
        View view(){net.tick(t.h.getLevel());return views.getLast();}
        @Override public void close(){if(!closed){closed=true;net.disconnected(t.owner.connection);t.close();}}
    }
    @GameCase(environment="chorus_gametest:charge_input_packets",maxTicks=25)
    public void pairedPacketsUseServerTokensAndCannotReleaseAnotherGestureOrDimension(GameTestHelper h)throws Exception{
        var t=openCharge(h);var n=new InputHarness(t);
        n.edge(Edge.PRESS);var first=charge(t);h.assertTrue(!first.token().equals("1"),"client chose domain token");
        h.assertValueEqual(n.net.request(t.owner,n.packet(1,1,Edge.PRESS)),WeaponChargeNetworkServer.Reply.DUPLICATE,"duplicate press");
        n.edge(Edge.PRESS);h.assertValueEqual(charge(t),first,"repeat press reset timing");
        h.assertValueEqual(n.edge(Edge.RELEASE),WeaponChargeNetworkServer.Reply.STALE,"wrong gesture released");
        h.assertValueEqual(n.net.request(t.owner,new Input(4,1,"test:elsewhere",Edge.CANCEL)),WeaponChargeNetworkServer.Reply.REJECTED,"wrong dimension accepted");
        h.assertValueEqual(charge(t),first,"rejected packets changed plan");n.sequence=4;n.gesture=1;
        var other=NativeMeleeGameTest.player(h);try{n.net.request(other,n.packet(1,1,Edge.PRESS));h.assertValueEqual(charge(t),first,"another player claimed charge");}finally{n.net.disconnected(other.connection);other.discard();}
        at(t,4,()->{try(n){n.edge(Edge.RELEASE);h.assertValueEqual(n.view().status(),Status.FIRED,"paired release");h.assertValueEqual(ammo(t),9,"one payment");h.assertValueEqual(t.projectiles.size(),1,"one native projectile");
            h.assertValueEqual(n.edge(Edge.RELEASE),WeaponChargeNetworkServer.Reply.STALE,"duplicate release accepted");h.assertValueEqual(t.projectiles.size(),1,"duplicate projectile");h.succeed();}});
    }
    @GameCase(environment="chorus_gametest:charge_input_progress",maxTicks=65)
    public void heartbeatsPreserveTimingAndProgressIsAnAuthoritativeServerSample(GameTestHelper h)throws Exception{
        var t=openCharge(h);var n=new InputHarness(t);n.heartbeat=true;n.edge(Edge.PRESS);var first=charge(t);
        at(t,4,()->{var v=n.view();h.assertValueEqual(v.phase(),Phase.CHARGING,"half phase");h.assertValueEqual(v.fraction(),.5,"half progress");h.assertValueEqual(v.minimumAt()-v.startedAt(),100_000L,"minimum marker");});
        at(t,50,()->{try(n){var v=n.view();h.assertValueEqual(v.phase(),Phase.READY,"full phase");h.assertValueEqual(v.fraction(),1.0,"full progress");h.assertValueEqual(charge(t).startedAt(),first.startedAt(),"heartbeat reset timer");h.assertValueEqual(ammo(t),10,"held full draw paid");h.assertTrue(t.projectiles.isEmpty(),"full draw auto-fired");
            var b=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),h.getLevel().registryAccess());try{var input=n.packet(n.sequence,n.gesture,Edge.HOLD);Input.CODEC.encode(b,input);h.assertValueEqual(Input.CODEC.decode(b),input,"input codec");View.CODEC.encode(b,v);h.assertValueEqual(View.CODEC.decode(b),v,"projection codec");}finally{b.release();}
            n.edge(Edge.CANCEL);h.assertTrue(charge(t)==null&&!n.view().held(),"cancel retained full draw");h.succeed();}});
    }
    @GameCase(environment="chorus_gametest:charge_input_lease",maxTicks=55)
    public void exactLeaseExpiryRejectsLateHeartbeatAndReleaseWithoutRefiring(GameTestHelper h)throws Exception{
        var t=openCharge(h);var n=new InputHarness(t);n.edge(Edge.PRESS);
        at(t,39,()->h.assertTrue(n.view().held(),"early expiry"));
        at(t,40,()->{try(n){h.assertValueEqual(n.edge(Edge.HOLD),WeaponChargeNetworkServer.Reply.STALE,"late heartbeat revived gesture");h.assertTrue(charge(t)==null&&!n.view().held(),"expired charge remains");h.assertValueEqual(n.edge(Edge.RELEASE),WeaponChargeNetworkServer.Reply.STALE,"expired release fired");h.assertValueEqual(ammo(t),10,"expiry payment");n.edge(Edge.PRESS);h.assertTrue(n.view().held(),"fresh press rejected");h.succeed();}});
    }
    @GameCase(environment="chorus_gametest:charge_input_gate",maxTicks=25)
    public void restrictionsRetireTheGestureAndRecoveryNeedsAFreshPress(GameTestHelper h)throws Exception{
        try(var t=openCharge(h);var n=new InputHarness(t)){
            n.edge(Edge.PRESS);IncandescentGameTest.bind(t,"deny","test:charge_denied","a");h.assertTrue(!n.view().held()&&charge(t)==null,"restriction retained charge");
            t.runtime.unbind("deny");h.assertValueEqual(n.edge(Edge.HOLD),WeaponChargeNetworkServer.Reply.STALE,"recovery restarted held charge");h.assertTrue(charge(t)==null,"automatic reentry");
            n.edge(Edge.PRESS);h.assertTrue(n.view().held(),"fresh recovery failed");t.owner.setGameMode(GameType.SPECTATOR);h.assertTrue(!n.view().held()&&charge(t)==null,"spectator kept charge");
            t.owner.setGameMode(GameType.SURVIVAL);h.assertValueEqual(n.edge(Edge.RELEASE),WeaponChargeNetworkServer.Reply.STALE,"spectator release fired");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:charge_input_reload",maxTicks=30)
    public void acceptedReloadAndUnequipDoNotRestartChargingFromHeartbeat(GameTestHelper h)throws Exception{
        var t=openCharge(h);var n=new InputHarness(t);n.edge(Edge.PRESS);
        at(t,2,()->{n.edge(Edge.RELEASE);at(t,3,()->{n.edge(Edge.PRESS);t.runtime.reload(t.owner);h.assertTrue(!n.view().held()&&charge(t)==null,"reload retained network gesture");
            at(t,4,()->{try(n){h.assertValueEqual(n.edge(Edge.HOLD),WeaponChargeNetworkServer.Reply.STALE,"reload restarted draw");n.edge(Edge.PRESS);h.assertTrue(n.view().held(),"fresh after reload");var equipment=PlayerEquipment.get(t.owner);equipment.draw(t.owner,Optional.empty(),equipment.revision());h.assertTrue(!n.view().held()&&charge(t)==null,"stow retained input");h.assertValueEqual(t.projectiles.size(),1,"cancel created shot");h.succeed();}});
        });});
    }
    @GameCase(environment="chorus_gametest:charge_input_runtime",maxTicks=25)
    public void runtimeRestoreKeepsDomainDataButDoesNotTransferTheLiveGesture(GameTestHelper h)throws Exception{
        var t=openCharge(h);var n=new InputHarness(t);n.edge(Edge.PRESS);var checkpoint=t.runtime.checkpoint();var program=t.runtime.program();t.runtime.close();
        try(var resumed=MinecraftEffectRuntime.restore(h.getLevel(),program,checkpoint,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),t.world,MinecraftEffectRuntime::nativeSource);n){
            h.assertTrue(resumed.state().engine().domain().charging().containsKey(holder(t)),"saved domain lost charge");h.assertTrue(!n.view().held()&&resumed.state().engine().domain().charging().isEmpty(),"restore inherited gesture");
            h.assertValueEqual(n.edge(Edge.RELEASE),WeaponChargeNetworkServer.Reply.STALE,"restored old release fired");n.edge(Edge.PRESS);h.assertTrue(n.view().held(),"fresh restored press rejected");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:charge_input_life",maxTicks=25)
    public void nativeRespawnRetiresTheOldConnectionGestureAndDelayedRelease(GameTestHelper h)throws Exception{
        try(var t=openCharge(h);var n=new InputHarness(t)){
            n.edge(Edge.PRESS);t.owner.setHealth(0);
            t.owner.connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            var replacement=t.owner.connection.player;t.entities.add(replacement);h.assertTrue(replacement!=t.owner,"native respawn did not replace connection player");
            h.assertValueEqual(replacement.getId(),t.owner.getId(),"native respawn reuses entity id; it is not a life identity");
            h.assertValueEqual(n.net.request(replacement,n.packet(2,1,Edge.RELEASE)),WeaponChargeNetworkServer.Reply.STALE,"new player life released old hold");
            h.assertTrue(charge(t)==null&&t.projectiles.isEmpty(),"respawn retained charge/shot");h.assertValueEqual(ammo(t),10,"respawn payment");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:charge_input_unknown",maxTicks=25)
    public void unknownReleaseRetiresSequenceAndNeverRetriesTheAcceptedPayment(GameTestHelper h)throws Exception{
        var t=openCharge(h);var n=new InputHarness(t);n.edge(Edge.PRESS);
        at(t,4,()->{try(n){t.failAfterCue=true;h.assertValueEqual(n.edge(Edge.RELEASE),WeaponChargeNetworkServer.Reply.FAILED,"unknown release");h.assertTrue(t.runtime.failure().isPresent()&&charge(t)==null,"failed release restored charge");h.assertValueEqual(ammo(t),9,"known payment rolled back");int operations=t.operations.size();
            h.assertValueEqual(n.net.request(t.owner,n.packet(2,1,Edge.RELEASE)),WeaponChargeNetworkServer.Reply.DUPLICATE,"unknown release replayed");h.assertValueEqual(n.view().status(),Status.FAILED,"failed projection");h.assertValueEqual(t.operations.size(),operations,"tick retried world work");h.succeed();}});
    }
    @GameCase(environment="chorus_gametest:charge_input_cleanup",maxTicks=25)
    public void disconnectCleansCommandPlansAndQuiescenceRemovesNetworkPlansBeforeSave(GameTestHelper h)throws Exception{
        try(var t=openCharge(h);var n=new InputHarness(t)){
            n.edge(Edge.PRESS);var old=charge(t);t.runtime.cancelCharge(holder(t),"host_replaced");
            t.runtime.charge(t.owner,WeaponCharge.Edge.PRESS);var replacement=charge(t);
            t.runtime.cancelCharge(holder(t),old.token(),"late_cancel");h.assertValueEqual(charge(t),replacement,"old token cancelled another host plan");
            h.assertValueEqual(n.edge(Edge.CANCEL),WeaponChargeNetworkServer.Reply.STALE,"old network cancel claimed new host plan");h.assertValueEqual(charge(t),replacement,"network cancelled unrelated host plan");
            n.net.disconnected(t.owner.connection);h.assertTrue(charge(t)==null,"disconnect retained replacement host plan");
            t.runtime.charge(t.owner,WeaponCharge.Edge.PRESS);n.net.disconnected(t.owner.connection);h.assertTrue(charge(t)==null,"command charge survived disconnect");
            n.edge(Edge.PRESS);n.net.quiesce(h.getLevel().getServer());h.assertTrue(t.runtime.checkpoint().effects().timeline().domain().charging().isEmpty(),"clean stop retained live gesture");
            int cues=t.cues.size();n.net.disconnected(t.owner.connection);n.net.tick(h.getLevel());h.assertValueEqual(t.cues.size(),cues,"cleanup replayed reaction");h.assertValueEqual(ammo(t),10,"cleanup paid");h.succeed();
        }
    }
}
