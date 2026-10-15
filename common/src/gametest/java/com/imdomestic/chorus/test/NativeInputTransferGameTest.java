package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.*;
import com.imdomestic.chorus.effect.ability.AbilityInput;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.network.*;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.portal.TeleportTransition;

/** Live connection gestures follow the same physical player through the production teleport hook. */
public class NativeInputTransferGameTest {
    static CompiledEffects program(){
        var data=NativeCharacterTransferGameTest.data();var weapon=data.getAsJsonArray("weapons").get(0).getAsJsonObject();
        weapon.add("aiming",object("""
            {"enter":{"value":{"type":"chorus:constant","value":0.2,"unit":"second"}},"exit":{"value":{"type":"chorus:constant","value":0.1,"unit":"second"}}}
            """));
        weapon.add("charging",object("""
            {"minimum":{"value":{"type":"chorus:constant","value":0.1,"unit":"second"}},"full":{"value":{"type":"chorus:constant","value":10,"unit":"second"}}}
            """));return compile(data);
    }
    static String dimension(net.minecraft.server.level.ServerLevel level){return level.dimension().identifier().toString();}
    static WeaponAimPayloads.Input aim(long sequence,String dimension,WeaponAimPayloads.Edge edge){return new WeaponAimPayloads.Input(sequence,1,dimension,edge);}
    static WeaponChargePayloads.Input charge(long sequence,String dimension,WeaponChargePayloads.Edge edge){return new WeaponChargePayloads.Input(sequence,1,dimension,edge);}
    static AbilityInputPayload ability(long sequence,String dimension,AbilityInput.Edge edge){return new AbilityInputPayload(sequence,1,dimension,"test:grenade",edge);}
    static void close(NativeCharacterTransferGameTest.Fixture f){
        try{WeaponAimNetworkServer.LIVE.disconnected(f.player.connection);WeaponChargeNetworkServer.LIVE.disconnected(f.player.connection);AbilityInputNetworkServer.LIVE.disconnected(f.player.connection);}
        finally{f.close();}
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void preparedNetworkHandoffsFreezeEdgesAndAbortPreservesOriginalGestures(GameTestHelper h){
        try(var f=new NativeCharacterTransferGameTest.Fixture(h,program())){
            var acknowledgements=new ArrayList<InputTransferPayload>();
            var aim=new WeaponAimNetworkServer((_,_)->{},(_,ack)->acknowledgements.add(ack));
            var charge=new WeaponChargeNetworkServer((_,_)->{},(_,ack)->acknowledgements.add(ack));
            var ability=new AbilityInputNetworkServer((_,ack)->acknowledgements.add(ack));String dimension=dimension(f.local);
            try{
                h.assertValueEqual(aim.request(f.player,aim(1,dimension,WeaponAimPayloads.Edge.PRESS)),WeaponAimNetworkServer.Reply.PROCESSED,"aim press");
                h.assertValueEqual(charge.request(f.player,charge(1,dimension,WeaponChargePayloads.Edge.PRESS)),WeaponChargeNetworkServer.Reply.PROCESSED,"charge press");
                h.assertValueEqual(ability.request(f.player,ability(1,dimension,AbilityInput.Edge.PRESS)).input().orElseThrow().outcome(),AbilityInput.Outcome.PRESSED,"ability press");
                var before=f.state(f.local);
                var a=aim.prepareTransfer(f.player,f.departure,f.arrival);var c=charge.prepareTransfer(f.player,f.departure,f.arrival);var b=ability.prepareTransfer(f.player,f.departure,f.arrival);
                h.assertValueEqual(aim.request(f.player,aim(2,dimension,WeaponAimPayloads.Edge.CANCEL)),WeaponAimNetworkServer.Reply.REJECTED,"frozen aim accepted edge");
                h.assertValueEqual(charge.request(f.player,charge(2,dimension,WeaponChargePayloads.Edge.CANCEL)),WeaponChargeNetworkServer.Reply.REJECTED,"frozen charge accepted edge");
                h.assertValueEqual(ability.request(f.player,ability(2,dimension,AbilityInput.Edge.CANCEL)).reply(),AbilityInputNetworkServer.Reply.REJECTED,"frozen ability accepted edge");
                aim.tick(f.local);charge.tick(f.local);h.assertValueEqual(f.state(f.local),before,"frozen network ran rules");
                b.abort();c.abort();a.abort();b.fail();c.fail();a.fail();
                h.assertValueEqual(aim.request(f.player,aim(2,dimension,WeaponAimPayloads.Edge.HOLD)),WeaponAimNetworkServer.Reply.PROCESSED,"abort lost aim or consumed frozen sequence");
                h.assertValueEqual(charge.request(f.player,charge(2,dimension,WeaponChargePayloads.Edge.HOLD)),WeaponChargeNetworkServer.Reply.PROCESSED,"abort lost charge or consumed frozen sequence");
                h.assertValueEqual(ability.request(f.player,ability(2,dimension,AbilityInput.Edge.RELEASE)).input().orElseThrow().outcome(),AbilityInput.Outcome.RELEASED,"abort lost ability gesture");
                near(h,f.state(f.local).resources().get(f.key()).value(),.5,"aborted preparation paid or repeated ability");
                var armed=f.state(f.local);var failed=charge.prepareTransfer(f.player,f.departure,f.arrival);failed.fail();
                h.assertValueEqual(f.state(f.local),armed,"failed handoff executed old-domain cancellation");
                h.assertTrue(acknowledgements.isEmpty(),"preparation/abort/failure sent transfer confirmation");
            }finally{aim.disconnected(f.player.connection);charge.disconnected(f.player.connection);ability.disconnected(f.player.connection);}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:native_input_transfer",maxTicks=15)
    public void nativeTransferKeepsAimChargeAndAbilityGesturesButRejectsOldDimensionEdges(GameTestHelper h){
        var f=new NativeCharacterTransferGameTest.Fixture(h,program());
        try{
            String from=dimension(f.local),to=dimension(f.remote);
            WeaponAimNetworkServer.LIVE.request(f.player,aim(1,from,WeaponAimPayloads.Edge.PRESS));
            WeaponChargeNetworkServer.LIVE.request(f.player,charge(1,from,WeaponChargePayloads.Edge.PRESS));
            AbilityInputNetworkServer.LIVE.request(f.player,ability(1,from,AbilityInput.Edge.PRESS));
            var accepted=f.state(f.local).charging().get(f.holder);
            h.runAfterDelay(3,()->{try{
                f.move(f.remote,TeleportTransition.DO_NOTHING);f.healthy();
                h.assertValueEqual(f.state(f.remote).charging().get(f.holder).token(),accepted.token(),"transfer replaced charge token");
                h.assertValueEqual(f.state(f.remote).charging().get(f.holder).startedAt(),accepted.startedAt(),"transfer restarted charge history");
                h.assertTrue(f.state(f.remote).aiming().containsKey(f.holder),"native transfer lost held aim");
                h.assertValueEqual(WeaponAimNetworkServer.LIVE.request(f.player,aim(2,from,WeaponAimPayloads.Edge.CANCEL)),WeaponAimNetworkServer.Reply.REJECTED,"old dimension cancelled migrated aim");
                h.assertValueEqual(WeaponChargeNetworkServer.LIVE.request(f.player,charge(2,from,WeaponChargePayloads.Edge.RELEASE)),WeaponChargeNetworkServer.Reply.REJECTED,"old dimension fired migrated charge");
                h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,ability(2,from,AbilityInput.Edge.RELEASE)).reply(),AbilityInputNetworkServer.Reply.REJECTED,"old dimension cast migrated ability");
                h.assertValueEqual(f.state(f.remote).ammunition().get(f.weapon("a")).magazine(),3,"rejected edge spent ammunition");
                near(h,f.state(f.remote).resources().get(f.key()).value(),.75,"rejected edge spent energy");
                h.assertValueEqual(WeaponAimNetworkServer.LIVE.request(f.player,new WeaponAimPayloads.Input(3,1,to,WeaponAimPayloads.Edge.HOLD,WeaponAimNetworkServer.LIVE.transferRevision(f.player))),WeaponAimNetworkServer.Reply.PROCESSED,"new-domain heartbeat lost original gesture");
                h.assertValueEqual(WeaponChargeNetworkServer.LIVE.request(f.player,new WeaponChargePayloads.Input(3,1,to,WeaponChargePayloads.Edge.RELEASE,WeaponChargeNetworkServer.LIVE.transferRevision(f.player))),WeaponChargeNetworkServer.Reply.PROCESSED,"new-domain charge release failed");
                h.assertValueEqual(f.state(f.remote).ammunition().get(f.weapon("a")).magazine(),2,"migrated charge release did not pay once");
                var released=new AbilityInputPayload(3,1,to,"test:grenade",AbilityInput.Edge.RELEASE,AbilityInputNetworkServer.LIVE.transferRevision(f.player,"test:grenade"));
                var release=AbilityInputNetworkServer.LIVE.request(f.player,released).input().orElseThrow();
                h.assertValueEqual(release.outcome(),AbilityInput.Outcome.RELEASED,"migrated ability release lost hold");
                h.assertTrue(release.heldMicros()>=150_000,"ability hold duration restarted in destination epoch");
                near(h,f.state(f.remote).resources().get(f.key()).value(),.5,"migrated ability did not pay once");
                h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,released).reply(),AbilityInputNetworkServer.Reply.DUPLICATE,"migrated release replayed");
                f.healthy();h.succeed();
            }finally{close(f);}});
        }catch(RuntimeException|Error error){close(f);throw error;}
    }
    @GameCase(environment="chorus_gametest:native_input_lease_transfer",maxTicks=50)
    public void nativeTransferPreservesAllInputLeasesAndExpiredHoldsRecoverWithFreshPress(GameTestHelper h){
        var f=new NativeCharacterTransferGameTest.Fixture(h,program());
        try{
            String from=dimension(f.local),to=dimension(f.remote);
            long[] abilityEpoch={0};
            WeaponAimNetworkServer.LIVE.request(f.player,aim(1,from,WeaponAimPayloads.Edge.PRESS));
            WeaponChargeNetworkServer.LIVE.request(f.player,charge(1,from,WeaponChargePayloads.Edge.PRESS));
            AbilityInputNetworkServer.LIVE.request(f.player,ability(1,from,AbilityInput.Edge.PRESS));
            h.runAfterDelay(38,()->{try{
                h.assertTrue(f.state(f.local).charging().containsKey(f.holder),"lease expired before transfer");
                f.move(f.remote,TeleportTransition.DO_NOTHING);
                h.assertTrue(f.state(f.remote).charging().containsKey(f.holder),"transfer retired unexpired charge");
                abilityEpoch[0]=AbilityInputNetworkServer.LIVE.transferRevision(f.player,"test:grenade");
                h.assertTrue(abilityEpoch[0]>0&&f.arrival.hasAbilityInput(f.player,"test:grenade",1),"transfer retired an unexpired ability hold");
            }catch(RuntimeException|Error error){close(f);throw error;}});
            h.runAfterDelay(40,()->{try{
                WeaponAimNetworkServer.LIVE.tick(f.remote);WeaponChargeNetworkServer.LIVE.tick(f.remote);AbilityInputNetworkServer.LIVE.tick(f.remote);
                h.assertTrue(f.state(f.remote).charging().isEmpty()&&f.state(f.remote).aiming().isEmpty(),"transfer renewed an expired lease");
                h.assertValueEqual(WeaponAimNetworkServer.LIVE.request(f.player,new WeaponAimPayloads.Input(2,1,to,WeaponAimPayloads.Edge.HOLD,WeaponAimNetworkServer.LIVE.transferRevision(f.player))),WeaponAimNetworkServer.Reply.STALE,"late aim heartbeat revived old hold");
                h.assertValueEqual(WeaponChargeNetworkServer.LIVE.request(f.player,new WeaponChargePayloads.Input(2,1,to,WeaponChargePayloads.Edge.HOLD,WeaponChargeNetworkServer.LIVE.transferRevision(f.player))),WeaponChargeNetworkServer.Reply.STALE,"late charge heartbeat revived old hold");
                h.assertTrue(!f.arrival.hasAbilityInput(f.player,"test:grenade",1),"missing migration ACK left a permanent ability hold");
                h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(2,1,to,"test:grenade",AbilityInputPayload.Edge.HOLD,abilityEpoch[0])).reply(),AbilityInputNetworkServer.Reply.REJECTED,"late ACK heartbeat revived expired ability");
                h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(3,1,to,"test:grenade",AbilityInput.Edge.RELEASE,abilityEpoch[0])).reply(),AbilityInputNetworkServer.Reply.REJECTED,"late ACK release cast an expired ability");
                near(h,f.state(f.remote).resources().get(f.key()).value(),.75,"lost ACK or lease expiry cast ability");
                h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(4,4,to,"test:grenade",AbilityInput.Edge.PRESS)).input().orElseThrow().outcome(),AbilityInput.Outcome.PRESSED,"lost ACK permanently blocked fresh input");
                h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(5,4,to,"test:grenade",AbilityInput.Edge.RELEASE)).input().orElseThrow().outcome(),AbilityInput.Outcome.RELEASED,"fresh ability after timeout did not cast");
                near(h,f.state(f.remote).resources().get(f.key()).value(),.5,"fresh post-timeout ability did not pay once");
                h.assertValueEqual(f.state(f.remote).ammunition().get(f.weapon("a")).magazine(),3,"lease expiry fired charge");f.healthy();h.succeed();
            }finally{close(f);}});
        }catch(RuntimeException|Error error){close(f);throw error;}
    }
    @GameCase(environment="chorus_gametest:native_ability_input_lease",maxTicks=80)
    public void abilityHeartbeatsRenewOnlyTheMatchingMigratedGestureAndServerTicksRetireSilence(GameTestHelper h){
        var f=new NativeCharacterTransferGameTest.Fixture(h,program());long[] epoch={0};
        try{
            String from=dimension(f.local),to=dimension(f.remote);
            AbilityInputNetworkServer.LIVE.request(f.player,ability(1,from,AbilityInput.Edge.PRESS));
            h.runAfterDelay(10,()->{try{
                h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(2,1,from,"test:grenade",AbilityInputPayload.Edge.HOLD)).reply(),AbilityInputNetworkServer.Reply.PROCESSED,"valid heartbeat did not renew ability");
            }catch(RuntimeException|Error error){close(f);throw error;}});
            h.runAfterDelay(20,()->{try{f.move(f.remote,TeleportTransition.DO_NOTHING);epoch[0]=AbilityInputNetworkServer.LIVE.transferRevision(f.player,"test:grenade");}
                catch(RuntimeException|Error error){close(f);throw error;}});
            h.runAfterDelay(30,()->{try{
                h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(3,1,to,"test:grenade",AbilityInputPayload.Edge.HOLD,epoch[0])).reply(),AbilityInputNetworkServer.Reply.PROCESSED,"matching destination heartbeat did not renew ability");
            }catch(RuntimeException|Error error){close(f);throw error;}});
            h.runAfterDelay(50,()->{try{
                h.assertTrue(f.arrival.hasAbilityInput(f.player,"test:grenade",1),"valid heartbeat failed to extend original gesture past initial lease");
                h.assertTrue(f.arrival.heldAbilityInput(f.player,"test:grenade").orElseThrow()>=2_500_000,"heartbeat restarted authoritative ability hold duration");
                h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(4,1,from,"test:grenade",AbilityInputPayload.Edge.HOLD)).reply(),AbilityInputNetworkServer.Reply.REJECTED,"old dimension heartbeat renewed ability");
                h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(5,1,to,"test:grenade",AbilityInputPayload.Edge.HOLD)).reply(),AbilityInputNetworkServer.Reply.REJECTED,"old epoch heartbeat renewed ability");
                h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(6,2,to,"test:grenade",AbilityInputPayload.Edge.HOLD,epoch[0])).reply(),AbilityInputNetworkServer.Reply.REJECTED,"different gesture heartbeat renewed ability");
            }catch(RuntimeException|Error error){close(f);throw error;}});
            h.runAfterDelay(71,()->{try{
                // No explicit network tick here: both loader registrations must retire the silent hold.
                h.assertTrue(!f.arrival.hasAbilityInput(f.player,"test:grenade",1),"server ticks did not expire ability authority");
                near(h,f.state(f.remote).resources().get(f.key()).value(),.75,"heartbeat or silence paid for a cast");f.healthy();h.succeed();
            }finally{close(f);}});
        }catch(RuntimeException|Error error){close(f);throw error;}
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void sameTickRoundTripRejectsOriginalDimensionPacketsUntilTheirExplicitEpochMatches(GameTestHelper h){
        var f=new NativeCharacterTransferGameTest.Fixture(h,program());
        try{
            String original=dimension(f.local);
            WeaponAimNetworkServer.LIVE.request(f.player,aim(1,original,WeaponAimPayloads.Edge.PRESS));
            WeaponChargeNetworkServer.LIVE.request(f.player,charge(1,original,WeaponChargePayloads.Edge.PRESS));
            AbilityInputNetworkServer.LIVE.request(f.player,ability(1,original,AbilityInput.Edge.PRESS));
            long[] intermediate=new long[3];
            f.move(f.remote,TeleportTransition.DO_NOTHING);
            intermediate[0]=WeaponAimNetworkServer.LIVE.transferRevision(f.player);intermediate[1]=WeaponChargeNetworkServer.LIVE.transferRevision(f.player);
            intermediate[2]=AbilityInputNetworkServer.LIVE.transferRevision(f.player,"test:grenade");f.move(f.local,TeleportTransition.DO_NOTHING);
            h.assertTrue(WeaponAimNetworkServer.LIVE.transferRevision(f.player)>intermediate[0]&&WeaponChargeNetworkServer.LIVE.transferRevision(f.player)>intermediate[1]
                    &&AbilityInputNetworkServer.LIVE.transferRevision(f.player,"test:grenade")>intermediate[2],"round trip did not advance gesture epochs");
            h.assertValueEqual(WeaponAimNetworkServer.LIVE.request(f.player,aim(2,original,WeaponAimPayloads.Edge.CANCEL)),WeaponAimNetworkServer.Reply.REJECTED,"old A packet cancelled returned A aim");
            h.assertValueEqual(WeaponChargeNetworkServer.LIVE.request(f.player,charge(2,original,WeaponChargePayloads.Edge.CANCEL)),WeaponChargeNetworkServer.Reply.REJECTED,"old A packet cancelled returned A charge");
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,ability(2,original,AbilityInput.Edge.CANCEL)).reply(),AbilityInputNetworkServer.Reply.REJECTED,"old A packet cancelled returned A ability");
            h.assertTrue(f.state(f.local).aiming().containsKey(f.holder)&&f.state(f.local).charging().containsKey(f.holder)&&f.departure.hasAbilityInput(f.player,"test:grenade",1),"old epoch retired transferred holds");
            h.assertValueEqual(WeaponAimNetworkServer.LIVE.request(f.player,new WeaponAimPayloads.Input(3,1,original,WeaponAimPayloads.Edge.CANCEL,WeaponAimNetworkServer.LIVE.transferRevision(f.player))),WeaponAimNetworkServer.Reply.PROCESSED,"current epoch could not cancel aim");
            h.assertValueEqual(WeaponChargeNetworkServer.LIVE.request(f.player,new WeaponChargePayloads.Input(3,1,original,WeaponChargePayloads.Edge.CANCEL,WeaponChargeNetworkServer.LIVE.transferRevision(f.player))),WeaponChargeNetworkServer.Reply.PROCESSED,"current epoch could not cancel charge");
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(3,1,original,"test:grenade",AbilityInput.Edge.CANCEL,AbilityInputNetworkServer.LIVE.transferRevision(f.player,"test:grenade"))).input().orElseThrow().outcome(),AbilityInput.Outcome.CANCELLED,"current epoch could not cancel ability");
            h.assertValueEqual(WeaponAimNetworkServer.LIVE.request(f.player,new WeaponAimPayloads.Input(4,4,original,WeaponAimPayloads.Edge.PRESS)),WeaponAimNetworkServer.Reply.PROCESSED,"fresh gesture could not restart at epoch zero");
            h.assertValueEqual(WeaponChargeNetworkServer.LIVE.request(f.player,new WeaponChargePayloads.Input(4,4,original,WeaponChargePayloads.Edge.PRESS)),WeaponChargeNetworkServer.Reply.PROCESSED,"fresh charge retained retired transfer epoch");
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(4,4,original,"test:grenade",AbilityInput.Edge.PRESS)).input().orElseThrow().outcome(),AbilityInput.Outcome.PRESSED,"fresh ability retained retired transfer epoch");
            h.assertTrue(WeaponAimNetworkServer.LIVE.transferRevision(f.player)==0&&WeaponChargeNetworkServer.LIVE.transferRevision(f.player)==0&&AbilityInputNetworkServer.LIVE.transferRevision(f.player,"test:grenade")==0,"new gestures inherited old migration epoch");
            h.assertValueEqual(WeaponAimNetworkServer.LIVE.request(f.player,new WeaponAimPayloads.Input(5,1,original,WeaponAimPayloads.Edge.CANCEL)),WeaponAimNetworkServer.Reply.STALE,"old gesture cancelled a fresh aim");
            h.assertValueEqual(WeaponChargeNetworkServer.LIVE.request(f.player,new WeaponChargePayloads.Input(5,1,original,WeaponChargePayloads.Edge.CANCEL)),WeaponChargeNetworkServer.Reply.STALE,"old gesture cancelled a fresh charge");
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(5,1,original,"test:grenade",AbilityInput.Edge.CANCEL)).input().orElseThrow().outcome(),AbilityInput.Outcome.STALE,"old gesture cancelled a fresh ability");
            h.assertTrue(f.state(f.local).aiming().containsKey(f.holder)&&f.state(f.local).charging().containsKey(f.holder)&&f.departure.hasAbilityInput(f.player,"test:grenade",4),"late cancellation retired a different physical gesture");
            h.assertValueEqual(f.state(f.local).ammunition().get(f.weapon("a")).magazine(),3,"round trip/cancellation spent ammo");near(h,f.state(f.local).resources().get(f.key()).value(),.75,"round trip/cancellation spent energy");f.healthy();
        }finally{close(f);}h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void actualRespawnRetiresStaleAbilityMetadataBeforeTheNewLifeTeleports(GameTestHelper h){
        var f=new NativeCharacterTransferGameTest.Fixture(h,program());net.minecraft.server.level.ServerPlayer replacement=null;
        try{
            String from=dimension(f.local),to=dimension(f.remote),life=MinecraftEntityLife.get(f.player).id();
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,ability(1,from,AbilityInput.Edge.PRESS)).input().orElseThrow().outcome(),AbilityInput.Outcome.PRESSED,"old life ability press");
            f.player.hurtServer(f.local,f.local.damageSources().genericKill(),1000);h.assertTrue(f.player.isDeadOrDying(),"fixture did not actually die");
            f.player.connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            replacement=f.player.connection.player;
            replacement.setPos(f.home);replacement.setNoGravity(true);replacement.setHealth(20);
            replacement.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
            h.assertTrue(replacement!=f.player&&replacement.connection==f.player.connection&&replacement.getUUID().equals(f.player.getUUID())&&!MinecraftEntityLife.get(replacement).id().equals(life),"respawn did not replace the same connection with a new life");
            f.departure.prepare();h.assertTrue(!f.departure.hasAbilityInput(replacement,"test:grenade",1),"new life inherited old hold");
            // No input packet has rebound the network session to the replacement before its actual native move.
            var position=new net.minecraft.world.phys.Vec3(12,90,12);f.remote.getChunk(net.minecraft.core.BlockPos.containing(position));
            h.assertTrue(replacement.teleport(new TeleportTransition(f.remote,position,net.minecraft.world.phys.Vec3.ZERO,0,0,TeleportTransition.DO_NOTHING))==replacement,"stale old-life metadata blocked native transfer");
            h.assertTrue(replacement.level()==f.remote&&f.remote.getEntity(replacement.getUUID())==replacement,"replacement was not indexed in destination");
            h.assertTrue(!f.arrival.hasAbilityInput(replacement,"test:grenade",1),"native transfer revived old-life hold");
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.transferRevision(replacement,"test:grenade"),0L,"old-life gesture received transfer authority");
            near(h,f.state(f.remote).resources().get(f.key()).value(),.75,"respawn/native transfer refunded or spent ability energy");
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(replacement,ability(2,to,AbilityInput.Edge.RELEASE)).input().orElseThrow().outcome(),AbilityInput.Outcome.NO_PRESS,"new life released the old gesture");
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(replacement,new AbilityInputPayload(3,3,to,"test:grenade",AbilityInput.Edge.PRESS)).input().orElseThrow().outcome(),AbilityInput.Outcome.PRESSED,"new life could not start a fresh gesture");
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(replacement,new AbilityInputPayload(4,3,to,"test:grenade",AbilityInput.Edge.RELEASE)).input().orElseThrow().outcome(),AbilityInput.Outcome.RELEASED,"fresh replacement gesture did not cast");
            near(h,f.state(f.remote).resources().get(f.key()).value(),.5,"fresh replacement cast did not pay exactly once");f.healthy();
        }finally{
            try{if(replacement!=null){try{AbilityInputNetworkServer.LIVE.disconnected(replacement.connection);}finally{f.local.getServer().getPlayerList().remove(replacement);replacement.discard();}}}
            finally{close(f);}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void transferConfirmationsRoundTripAllGestureKindsAndRejectAmbiguousAuthorizations(GameTestHelper h){
        var player=UUID.randomUUID();var messages=new ArrayList<InputTransferPayload>();
        for(var kind:InputTransferPayload.Kind.values())messages.add(new InputTransferPayload(kind,kind==InputTransferPayload.Kind.ABILITY?"test:grenade":"",player,7,"minecraft:overworld","minecraft:the_nether",12,3,9,5));
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),h.getLevel().registryAccess());
        try{for(var message:messages)InputTransferPayload.CODEC.encode(buffer,message);for(var message:messages)h.assertValueEqual(InputTransferPayload.CODEC.decode(buffer),message,"transfer confirmation changed on wire");
            var aim=new WeaponAimPayloads.Input(12,3,"minecraft:the_nether",WeaponAimPayloads.Edge.HOLD,9);WeaponAimPayloads.Input.CODEC.encode(buffer,aim);h.assertValueEqual(WeaponAimPayloads.Input.CODEC.decode(buffer),aim,"aim input lost epoch");
            var charge=new WeaponChargePayloads.Input(12,3,"minecraft:the_nether",WeaponChargePayloads.Edge.RELEASE,9);WeaponChargePayloads.Input.CODEC.encode(buffer,charge);h.assertValueEqual(WeaponChargePayloads.Input.CODEC.decode(buffer),charge,"charge input lost epoch");
            for(var edge:AbilityInputPayload.Edge.values()){
                var ability=new AbilityInputPayload(12,edge==AbilityInputPayload.Edge.PRESS?12:3,"minecraft:the_nether","test:grenade",edge,9);
                AbilityInputPayload.CODEC.encode(buffer,ability);h.assertValueEqual(AbilityInputPayload.CODEC.decode(buffer),ability,"ability input lost network edge or epoch");
            }h.assertTrue(!buffer.isReadable(),"transfer codec left unread bytes");}finally{buffer.release();}
        refused(h,IllegalArgumentException.class,()->new InputTransferPayload(InputTransferPayload.Kind.AIM,"test:slot",player,7,"minecraft:overworld","minecraft:the_nether",12,3,9));
        refused(h,IllegalArgumentException.class,()->new InputTransferPayload(InputTransferPayload.Kind.CHARGE,"",player,7,"minecraft:overworld","minecraft:overworld",12,3,9));
        refused(h,IllegalArgumentException.class,()->new InputTransferPayload(InputTransferPayload.Kind.AIM,"",player,7,"minecraft:overworld","minecraft:the_nether",12,3,9,9));
        refused(h,IllegalArgumentException.class,()->new InputTransferPayload(InputTransferPayload.Kind.ABILITY,"test:grenade",player,7,"minecraft:overworld","minecraft:the_nether",2,3,9));h.succeed();
    }
}
