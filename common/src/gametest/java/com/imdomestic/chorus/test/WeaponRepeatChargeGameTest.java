package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.WeaponChargeGameTest.*;
import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.ExplosivePayloadGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.network.WeaponChargePayloads.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.effect.weapon.WeaponCharge;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

/** Real network authority and physical fusion salvos, with explicit synthetic damage/flight calibration. */
public class WeaponRepeatChargeGameTest {
    static WeaponAutoChargeGameTest.Wire open(GameTestHelper h, boolean warm) throws Exception {
        return new WeaponAutoChargeGameTest.Wire(ControlledBurstGameTest.open(h, false, warm, true));
    }
    @GameCase(environment="chorus_gametest:repeat_charge_cycles", maxTicks=80)
    public void oneNetworkPressDrivesTwoWholeSalvosAndReleaseStopsTheRecovery(GameTestHelper h) throws Exception {
        var n=open(h,false);var t=n.t;n.edge(Edge.PRESS);String token=charge(t).token();
        at(t,18,()->{settled(t);h.assertValueEqual(t.projectiles.size(),7,"first complete salvo");h.assertValueEqual(n.view().phase(),Phase.RECOVERING,"authoritative recovery phase");h.assertTrue(n.view().held(),"recovery lost physical gesture");});
        at(t,20,()->n.edge(Edge.HOLD));
        at(t,27,()->{settled(t);h.assertValueEqual(charge(t).cycle(),1L,"next cycle at recovery deadline");h.assertValueEqual(charge(t).token(),token,"server restarted physical gesture");});
        at(t,40,()->n.edge(Edge.HOLD));
        at(t,44,()->{settled(t);h.assertValueEqual(t.projectiles.size(),14,"second whole salvo");h.assertValueEqual(ammo(t),3,"one payment per salvo");n.edge(Edge.RELEASE);h.assertValueEqual(n.view().status(),Status.CANCELLED,"release during recovery");});
        at(t,65,()->{try(n){settled(t);h.assertTrue(charge(t)==null,"released hold rearmed");h.assertValueEqual(t.projectiles.size(),14,"late third salvo");h.succeed();}});
    }
    @GameCase(environment="chorus_gametest:repeat_charge_perks", maxTicks=55)
    public void firstSalvoKillChangesTheNextAutomaticCyclesAcceptedDuration(GameTestHelper h) throws Exception {
        var n=open(h,true);var t=n.t;n.edge(Edge.PRESS);
        at(t,14,()->{settled(t);var weak=primary(t,t.projectiles.getFirst(),12);weak.setHealth(1);contact(t,t.projectiles.getFirst());h.assertTrue(!weak.isAlive(),"physical first-bolt kill");weak.discard();});
        at(t,20,()->n.edge(Edge.HOLD));
        at(t,27,()->{settled(t);near(h,charge(t).full().duration().value(),.667*.9*.625,"next cycle resamples both perk families");h.assertValueEqual(charge(t).cycle(),1L,"second cycle");});
        at(t,35,()->{try(n){settled(t);h.assertTrue(t.projectiles.size()>=8,"accelerated next salvo");h.assertValueEqual(ammo(t),3,"second automatic payment");var target=primary(t,t.projectiles.get(7),12);contact(t,t.projectiles.get(7));near(h,target.getHealth(),88,"automatic next-cycle damage buff");n.edge(Edge.CANCEL);h.succeed();}});
    }
    @GameCase(environment="chorus_gametest:repeat_charge_lease", maxTicks=30)
    public void expiredLeaseCannotReplayMultipleHistoricalCyclesDuringClockCatchup(GameTestHelper h) throws Exception {
        var n=open(h,false);var t=n.t;n.edge(Edge.PRESS);
        at(t,18,()->{settled(t);WeaponAutoChargeGameTest.future(n,60,()->{t.runtime.prepare();h.assertTrue(t.runtime.failure().isEmpty(),"expired repeat faulted runtime");h.assertTrue(charge(t)==null,"expired hold survived catchup");h.assertValueEqual(t.projectiles.size(),7,"catchup fired more salvos");h.assertValueEqual(ammo(t),4,"catchup paid again");});h.succeed();});
    }
    @GameCase(environment="chorus_gametest:repeat_charge_stow", maxTicks=55)
    public void stowAndImmediateRedrawRetireRecoveryBeforeTheNextNetworkTick(GameTestHelper h) throws Exception {
        var n=open(h,false);var t=n.t;n.edge(Edge.PRESS);
        at(t,18,()->{settled(t);draw(t,null);draw(t,"primary");h.assertTrue(charge(t)==null,"redraw revived recovery");n.edge(Edge.HOLD);h.assertTrue(!n.view().held(),"retired cycle left input active");});
        at(t,45,()->{try(n){settled(t);h.assertValueEqual(t.projectiles.size(),7,"stow/rehold generated another salvo");h.assertValueEqual(ammo(t),4,"stow refunded or repaid");h.succeed();}});
    }
    @GameCase(environment="chorus_gametest:repeat_charge_restore", maxTicks=30)
    public void restoredRecoveryHasNoLiveAuthorityToStartAnotherCycle(GameTestHelper h) throws Exception {
        var n=open(h,false);var t=n.t;n.edge(Edge.PRESS);
        at(t,18,()->{settled(t);var saved=t.runtime.checkpoint();var program=t.runtime.program();t.runtime.close();
            try(var restored=MinecraftEffectRuntime.restore(h.getLevel(),program,saved,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),t.world,MinecraftEffectRuntime::nativeSource)){
                WeaponAutoChargeGameTest.future(n,30,()->{restored.prepare();var state=restored.state().engine().domain();h.assertTrue(restored.failure().isEmpty()&&state.charging().isEmpty(),"restored recovery reconstructed hold authority");h.assertValueEqual(state.ammunition().get("a").magazine(),4,"restored repeat paid again");h.assertValueEqual(t.projectiles.size(),7,"restored repeat fired");restored.close();});
            }h.succeed();
        });
    }
    @GameCase(environment="chorus_gametest:repeat_charge_respawn", maxTicks=55)
    public void nativeRespawnCannotTransferTheOldRecoveryToTheReplacementPlayer(GameTestHelper h) throws Exception {
        var n=open(h,false);var t=n.t;n.edge(Edge.PRESS);
        at(t,18,()->{settled(t);t.owner.setHealth(0);t.owner.connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));var replacement=t.owner.connection.player;t.entities.add(replacement);h.assertTrue(replacement!=t.owner,"native replacement missing");});
        at(t,45,()->{try(n){settled(t);h.assertTrue(charge(t)==null,"new life continued old recovery");h.assertValueEqual(t.projectiles.size(),7,"respawn inherited automatic fire");h.assertValueEqual(ammo(t),4,"respawn changed payment");h.succeed();}});
    }
    @GameCase(environment="chorus_gametest:repeat_charge_unknown", maxTicks=30)
    public void unknownPhysicalDamageStopsTheRemainingCycleWithoutRepeatingTheWorldWrite(GameTestHelper h) throws Exception {
        var n=open(h,false);var t=n.t;n.edge(Edge.PRESS);
        at(t,18,()->{try(n){settled(t);var target=primary(t,t.projectiles.getFirst(),12);t.failAfterDamage=true;var shot=t.projectiles.getFirst();for(int i=0;i<35&&!shot.isRemoved();i++)shot.tick();near(h,target.getHealth(),90,"already applied damage");h.assertTrue(t.runtime.failure().isPresent(),"unknown repeat damage did not stop");int ops=t.operations.size();n.edge(Edge.HOLD);n.edge(Edge.RELEASE);t.runtime.prepare();h.assertValueEqual(t.operations.size(),ops,"unknown repeat replayed world writes");h.assertValueEqual(ammo(t),4,"unknown repeat changed committed cost");h.succeed();}});
    }
    @GameCase(environment="chorus_gametest:repeat_charge_disconnect", maxTicks=30)
    public void disconnectRevokesRecoveryBeforeSettlingOverdueRepeatTimers(GameTestHelper h) throws Exception {
        var n=open(h,false);var t=n.t;n.edge(Edge.PRESS);
        at(t,18,()->{settled(t);WeaponAutoChargeGameTest.future(n,30,()->{n.net.disconnected(t.owner.connection);h.assertTrue(charge(t)==null,"disconnect retained repeat plan");h.assertValueEqual(t.projectiles.size(),7,"disconnect settled an unauthorized second salvo");h.assertValueEqual(ammo(t),4,"disconnect charged another round");});h.succeed();});
    }
    @GameCase(environment="chorus_gametest:repeat_charge_stop", maxTicks=30)
    public void cleanStopRevokesCommandRecoveryBeforeItsNextAutomaticCycle(GameTestHelper h) throws Exception {
        var n=open(h,false);var t=n.t;t.runtime.charge(t.owner,WeaponCharge.Edge.PRESS);
        at(t,18,()->{settled(t);WeaponAutoChargeGameTest.future(n,30,()->{MinecraftEffectRuntime.quiesceCharges(h.getLevel().getServer());h.assertTrue(charge(t)==null,"clean stop retained trusted command hold");h.assertValueEqual(t.projectiles.size(),7,"clean stop advanced command repeat before revoking it");h.assertValueEqual(ammo(t),4,"clean stop paid another round");});h.succeed();});
    }
    static void terminalBeforeCatchup(GameTestHelper h,Edge edge)throws Exception{
        var n=open(h,false);var t=n.t;n.edge(Edge.PRESS);
        at(t,18,()->{settled(t);n.edge(Edge.HOLD);WeaponAutoChargeGameTest.future(n,30,()->{
            h.assertValueEqual(n.edge(edge),com.imdomestic.chorus.network.WeaponChargeNetworkServer.Reply.PROCESSED,"matching terminal packet");
            h.assertTrue(charge(t)==null,"terminal packet retained recovery");h.assertValueEqual(t.projectiles.size(),7,"terminal packet first advanced a still-authorized overdue cycle");h.assertValueEqual(ammo(t),4,"terminal packet paid for catchup shot");
        });h.succeed();});
    }
    @GameCase(environment="chorus_gametest:repeat_charge_release_catchup", maxTicks=30)
    public void matchedReleaseRevokesAnUnexpiredLeaseBeforeHistoricalCyclesAdvance(GameTestHelper h)throws Exception{terminalBeforeCatchup(h,Edge.RELEASE);}
    @GameCase(environment="chorus_gametest:repeat_charge_cancel_catchup", maxTicks=30)
    public void matchedCancelRevokesAnUnexpiredLeaseBeforeHistoricalCyclesAdvance(GameTestHelper h)throws Exception{terminalBeforeCatchup(h,Edge.CANCEL);}
    static void commandTerminalBeforeCatchup(GameTestHelper h,WeaponCharge.Edge edge)throws Exception{
        var n=open(h,false);var t=n.t;t.runtime.charge(t.owner,WeaponCharge.Edge.PRESS);
        at(t,18,()->{settled(t);WeaponAutoChargeGameTest.future(n,30,()->{
            h.assertValueEqual(t.runtime.charge(t.owner,edge).outcome(),WeaponCharge.Outcome.CANCELLED,"command terminal receipt");
            h.assertTrue(charge(t)==null,"command retained recovery");h.assertValueEqual(t.projectiles.size(),7,"command advanced automatic cycles before retiring authority");h.assertValueEqual(ammo(t),4,"command paid for a catchup shot");
        });h.succeed();});
    }
    @GameCase(environment="chorus_gametest:repeat_charge_command_release", maxTicks=30)
    public void commandReleaseUsesTheSamePreAdvanceCancellationBoundary(GameTestHelper h)throws Exception{commandTerminalBeforeCatchup(h,WeaponCharge.Edge.RELEASE);}
    @GameCase(environment="chorus_gametest:repeat_charge_command_cancel", maxTicks=30)
    public void commandCancelUsesTheSamePreAdvanceCancellationBoundary(GameTestHelper h)throws Exception{commandTerminalBeforeCatchup(h,WeaponCharge.Edge.CANCEL);}
}
