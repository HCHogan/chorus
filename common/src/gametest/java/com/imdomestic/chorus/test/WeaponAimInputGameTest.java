package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.WeaponAimGameTest.*;
import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import static com.imdomestic.chorus.network.WeaponAimPayloads.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.network.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;

public class WeaponAimInputGameTest {
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void relocatedAimPayloadPreservesSignedLocalPhaseTimesThroughNativeCodec(GameTestHelper h) {
        var player=UUID.randomUUID();var dimension=h.getLevel().dimension().identifier().toString();
        var views=List.of(
                new View(1,1,1,player,dimension,true,Status.ACTIVE,Phase.ENTERING,"carried",-50_000,150_000),
                new View(2,1,1,player,dimension,true,Status.ACTIVE,Phase.AIMED,"carried",-300_000,-100_000),
                new View(3,2,1,player,dimension,false,Status.ACTIVE,Phase.EXITING,"carried",-20_000,80_000));
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),h.getLevel().registryAccess());
        try {
            for(var view:views)View.CODEC.encode(buffer,view);
            for(var view:views)h.assertValueEqual(View.CODEC.decode(buffer),view,"signed aim timestamp or phase changed on wire");
            h.assertTrue(!buffer.isReadable(),"aim decoder left unread packet fields");
        } finally {buffer.release();}
        ResourceProjectionGameTest.refused(h,IllegalArgumentException.class,()->new View(1,1,1,player,dimension,true,Status.ACTIVE,Phase.ENTERING,"carried",-10,-20));
        ResourceProjectionGameTest.refused(h,ArithmeticException.class,()->new View(1,1,1,player,dimension,true,Status.ACTIVE,Phase.ENTERING,"carried",Long.MIN_VALUE,0));
        h.succeed();
    }
    public static CompiledEffects program() {
        var data = ThreadedSpikeGameTest.json("box_breathing_weapon"); RadiantGameTest.merge(data, "box_breathing");
        return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
    }
    static final class InputHarness implements AutoCloseable {
        final ProjectileGameTest.Harness t; final List<View> views = new ArrayList<>();
        final WeaponAimNetworkServer net = new WeaponAimNetworkServer((_, view) -> views.add(view));
        long sequence, gesture; boolean closed, heartbeat;
        InputHarness(ProjectileGameTest.Harness t) {
            this.t = t;
            t.h.onEachTick(() -> { if (!closed) { if (heartbeat && gesture > 0 && t.h.getLevel().getGameTime() % 10 == 0) edge(Edge.HOLD); net.tick(t.h.getLevel()); } });
        }
        Input packet(long sequence, long gesture, Edge edge) { return new Input(sequence, gesture, t.h.getLevel().dimension().identifier().toString(), edge); }
        WeaponAimNetworkServer.Reply edge(Edge edge) { ++sequence; if (edge == Edge.PRESS) gesture = sequence; return net.request(t.owner, packet(sequence, gesture, edge)); }
        View view() { net.tick(t.h.getLevel()); return views.getLast(); }
        @Override public void close() { if (!closed) { closed = true; net.disconnected(t.owner.connection); t.close(); } }
    }
    @GameCase(environment="chorus_gametest:aim_input_packets", maxTicks=30)
    public void PairedPacketsAreOrderedAndCannotReleaseAnotherGestureOrDimension(GameTestHelper h)throws Exception {
        var t=openAim(h); var n=new InputHarness(t); at(t,6,()->{try(n){
            h.assertValueEqual(n.edge(Edge.PRESS),WeaponAimNetworkServer.Reply.PROCESSED,"press");var first=aim(t);
            h.assertValueEqual(n.net.request(t.owner,n.packet(1,1,Edge.PRESS)),WeaponAimNetworkServer.Reply.DUPLICATE,"duplicate press");
            n.edge(Edge.PRESS);h.assertValueEqual(aim(t),first,"new press reset a held gesture");
            h.assertValueEqual(n.edge(Edge.RELEASE),WeaponAimNetworkServer.Reply.STALE,"wrong release matched original press");
            h.assertValueEqual(n.net.request(t.owner,new Input(4,1,"test:elsewhere",Edge.CANCEL)),WeaponAimNetworkServer.Reply.REJECTED,"wrong dimension accepted");h.assertValueEqual(aim(t),first,"wrong dimension cancelled current aim");
            n.sequence=4;n.gesture=1;h.assertValueEqual(n.edge(Edge.RELEASE),WeaponAimNetworkServer.Reply.PROCESSED,"original release");
            h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.EXITING,"release phase");h.assertTrue(!n.view().held(),"projection retained intent");
            var b=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),h.getLevel().registryAccess());
            try {var input=n.packet(5,1,Edge.RELEASE);Input.CODEC.encode(b,input);h.assertValueEqual(Input.CODEC.decode(b),input,"input codec");var view=n.view();View.CODEC.encode(b,view);h.assertValueEqual(View.CODEC.decode(b),view,"view codec");}finally{b.release();}
            var other=NativeMeleeGameTest.player(h);try{n.net.request(other,n.packet(1,1,Edge.PRESS));h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.EXITING,"another player changed owner aim");h.assertTrue(!state(t).aiming().containsKey(other.getUUID().toString()),"empty owner gained weapon");}finally{n.net.disconnected(other.connection);other.discard();}
            h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:aim_input_switch", maxTicks=50)
    public void HeldIntentWaitsForReloadAndSwitchAndReleaseDuringReadyPreventsReentry(GameTestHelper h)throws Exception {
        var t=openAim(h);var n=new InputHarness(t);n.heartbeat=true;at(t,6,()->{
            n.edge(Edge.PRESS);var first=aim(t);t.runtime.reload(t.owner);h.assertValueEqual(n.view().status(),Status.BUSY,"reload must retain only intent");h.assertTrue(aim(t)==null,"reload aim not cancelled");
            at(t,4,()->{n.view();h.assertTrue(aim(t)!=null&&aim(t).startedAt()>first.startedAt(),"held reload did not create fresh entry");
                draw(t,"secondary");h.assertValueEqual(n.view().status(),Status.BUSY,"switch must wait");
                at(t,10,()->{n.view();h.assertValueEqual(aim(t).gear().instance(),"b","held switch did not aim newly owned weapon");
                    draw(t,"primary");n.edge(Edge.RELEASE);n.heartbeat=false;
                    at(t,14,()->{try(n){h.assertTrue(aim(t)==null&&!n.view().held(),"release during handling restarted aim");h.succeed();}});
                });
            });
        });
    }
    @GameCase(environment="chorus_gametest:aim_input_lease", maxTicks=60)
    public void MissingHeartbeatExpiresAtDeadlineAndLateHoldCannotResurrectIt(GameTestHelper h)throws Exception {
        var t=openAim(h);var n=new InputHarness(t);at(t,6,()->{
            n.edge(Edge.PRESS);at(t,39,()->{h.assertTrue(n.view().held()&&aim(t)!=null,"lease expired early");});
            at(t,40,()->{try(n){h.assertTrue(!n.view().held()&&aim(t)==null,"expired lease retained aim");h.assertValueEqual(n.edge(Edge.HOLD),WeaponAimNetworkServer.Reply.STALE,"late heartbeat revived intent");n.edge(Edge.PRESS);h.assertTrue(n.view().held()&&aim(t)!=null,"fresh press after expiry rejected");h.succeed();}});
        });
    }
    @GameCase(environment="chorus_gametest:aim_input_cancel", maxTicks=30)
    public void CancelSpectatorAndDisconnectRetireTheGestureWithoutAnExitWait(GameTestHelper h)throws Exception {
        var t=openAim(h);var n=new InputHarness(t);at(t,6,()->{try(n){
            n.edge(Edge.PRESS);n.edge(Edge.CANCEL);h.assertTrue(aim(t)==null&&!n.view().held(),"GUI cancel retained exit phase");
            n.edge(Edge.PRESS);t.owner.setGameMode(GameType.SPECTATOR);h.assertTrue(!n.view().held()&&aim(t)==null,"spectator retained input");t.owner.setGameMode(GameType.SURVIVAL);
            h.assertValueEqual(n.edge(Edge.HOLD),WeaponAimNetworkServer.Reply.STALE,"spectator return revived old input");n.edge(Edge.PRESS);n.net.disconnected(t.owner.connection);h.assertTrue(aim(t)==null,"disconnect retained input");h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:aim_input_gate", maxTicks=35)
    public void ContinueRestrictionInterruptsAimedStateAndRecoveryUsesANewEntry(GameTestHelper h)throws Exception {
        var t=openAim(h);var n=new InputHarness(t);at(t,6,()->{
            n.edge(Edge.PRESS);at(t,4,()->{var first=aim(t);h.assertValueEqual(n.view().phase(),Phase.AIMED,"aim never became ready");bind(t,"test:aim_denied",true);
                h.assertTrue(n.view().held()&&aim(t)==null,"continue denial did not cancel domain aim");h.assertValueEqual(n.view().status(),Status.RESTRICTED,"restriction not projected");
                bind(t,"test:aim_denied",false);n.view();h.assertTrue(aim(t)!=null&&aim(t).startedAt()>first.startedAt(),"restriction recovery kept old aim timer");
                at(t,4,()->{try(n){h.assertValueEqual(n.view().phase(),Phase.AIMED,"fresh entry did not complete");h.succeed();}});
            });
        });
    }
    @GameCase(environment="chorus_gametest:aim_input_runtime", maxTicks=30)
    public void RestoredDomainAimDoesNotInheritAnOldConnectionGesture(GameTestHelper h)throws Exception {
        var t=openAim(h);var n=new InputHarness(t);at(t,6,()->{
            n.edge(Edge.PRESS);var checkpoint=t.runtime.checkpoint();var program=t.runtime.program();t.runtime.close();
            try(var resumed=MinecraftEffectRuntime.restore(h.getLevel(),program,checkpoint,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),t.world,MinecraftEffectRuntime::nativeSource);n){
                h.assertTrue(resumed.state().engine().domain().aiming().containsKey(holder(t)),"checkpoint lost accepted phase before reconciliation");
                h.assertTrue(!n.view().held()&&!resumed.state().engine().domain().aiming().containsKey(holder(t)),"restored runtime inherited live input");
                h.assertValueEqual(n.edge(Edge.HOLD),WeaponAimNetworkServer.Reply.STALE,"old heartbeat revived restored input");n.edge(Edge.PRESS);h.assertTrue(n.view().held(),"fresh input after replacement failed");h.succeed();
            }
        });
    }
    @GameCase(environment="chorus_gametest:aim_input_unknown", maxTicks=30)
    public void UnknownAimReactionConsumesSequenceAndRetiresAutomaticRetries(GameTestHelper h)throws Exception {
        var t=openAim(h);var n=new InputHarness(t);at(t,6,()->{try(n){
            t.failAfterCue=true;h.assertValueEqual(n.edge(Edge.PRESS),WeaponAimNetworkServer.Reply.FAILED,"unknown input result");h.assertTrue(t.runtime.failure().isPresent()&&aim(t)!=null,"accepted aim was rolled back");
            int count=t.cues.size();h.assertValueEqual(n.net.request(t.owner,n.packet(1,1,Edge.PRESS)),WeaponAimNetworkServer.Reply.DUPLICATE,"unknown press replayed");
            h.assertTrue(!n.view().held()&&n.view().status()==Status.FAILED,"failed input still held");h.assertValueEqual(t.cues.size(),count,"tick replayed unknown effect");h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:aim_input_box", maxTicks=90)
    public void HeartbeatsKeepBoxBreathingAndReloadRequiresANewFullWait(GameTestHelper h)throws Exception {
        var t=BoxBreathingGameTest.openBox(h,false,_ -> {});t.runtime.cancelAim(holder(t),"test_reset");var n=new InputHarness(t);n.edge(Edge.PRESS);n.heartbeat=true;
        at(t,30,()->{
            settled(t);h.assertTrue(BoxBreathingGameTest.active(t),"heartbeats restarted Box Breathing wait");
            t.runtime.fire(t.owner);t.runtime.reload(t.owner);long expectedEntry=t.runtime.nowMicros()+200_000;h.assertTrue(!BoxBreathingGameTest.active(t)&&n.view().status()==Status.BUSY,"reload retained perk");
            at(t,4,()->{n.view();h.assertValueEqual(aim(t).startedAt(),expectedEntry,"reentry must start at completed reload");
                at(t,29,()->{settled(t);h.assertTrue(!BoxBreathingGameTest.active(t),"new wait completed early");});
                at(t,30,()->{try(n){settled(t);h.assertTrue(BoxBreathingGameTest.active(t),"full wait after reload did not activate");n.edge(Edge.RELEASE);h.assertTrue(!BoxBreathingGameTest.active(t),"release retained perk");h.succeed();}});
            });
        });
    }
    @GameCase(environment="chorus_gametest:aim_input_quiesce", maxTicks=30)
    public void QuiescenceClearsLiveAimBeforeCapturingACleanStopCheckpoint(GameTestHelper h)throws Exception {
        var t=openAim(h);var n=new InputHarness(t);at(t,6,()->{try(n){
            n.edge(Edge.PRESS);h.assertTrue(aim(t)!=null,"missing active input before stop");
            n.net.quiesce(h.getLevel().getServer());var saved=t.runtime.checkpoint();
            h.assertTrue(saved.effects().timeline().domain().aiming().isEmpty(),"clean-stop checkpoint retained live aim");
            int cues=t.cues.size();n.net.disconnected(t.owner.connection);n.net.tick(h.getLevel());
            h.assertValueEqual(t.cues.size(),cues,"post-quiescence disconnect replayed cancellation");h.succeed();
        }});
    }

}
