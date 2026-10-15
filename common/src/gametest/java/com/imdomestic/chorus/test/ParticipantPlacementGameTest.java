package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.ResourceRuntimeGameTest.domain;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.refused;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.ability.AbilityInput;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.resource.ParticipantPlacement;
import com.imdomestic.chorus.network.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;

/** Participant authority follows actual native transport independently of resource placement. */
public class ParticipantPlacementGameTest {
    static void placed(NativeCharacterTransferGameTest.Fixture f,ServerLevel level){
        var directory=f.host.participants();
        f.h.assertValueEqual(directory.characters().get(f.holder).domain(),domain(level),"character placement differs from native player");
        f.h.assertValueEqual(directory.buffs().get(f.holder).domain(),domain(level),"player buff placement differs from native player");
        f.h.assertTrue(f.player.level()==level&&level.getEntity(f.player.getUUID())==f.player,"placement lacks indexed physical player");
    }
    static CompiledEffects emptyBuffProgram(){
        var data=NativeCharacterTransferGameTest.data();data.getAsJsonArray("abilities").get(0).getAsJsonObject().add("on_use",new com.google.gson.JsonArray());
        return ResourceProjectionGameTest.compile(data);
    }
    static CompiledEffects weaponBuffProgram(){
        var data=NativeCharacterTransferGameTest.data();
        data.getAsJsonArray("buffs").add(JsonParser.parseString("""
            {"definition":{"id":"test:weapon_carry","version":"native-character-transfer","duration":5,"attach":"weapon"}}
            """));
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").add(JsonParser.parseString("""
            {"id":"weapon_buff","on":"test:weapon_buff","if":{"type":"chorus:source_is","source":"this_weapon"},
             "do":[{"type":"chorus:grant_buff","buff":"test:weapon_carry"}]}
            """));
        return ResourceProjectionGameTest.compile(data);
    }
    static BuffInstance weaponBuff(NativeCharacterTransferGameTest.Fixture f,ServerLevel level){
        return f.state(level).buffs().instances().values().stream().filter(b->b.key().holder().equals(f.weapon("a"))).findFirst().orElseThrow();
    }
    static void closeReplacement(NativeCharacterTransferGameTest.Fixture f){
        var players=Collections.newSetFromMap(new IdentityHashMap<ServerPlayer,Boolean>());
        players.add(f.player.connection.player);var indexed=f.local.getServer().getPlayerList().getPlayer(f.player.getUUID());if(indexed!=null)players.add(indexed);players.remove(f.player);
        try{f.host.close();}finally{
            try{
                WeaponAimNetworkServer.LIVE.disconnected(f.player.connection);WeaponChargeNetworkServer.LIVE.disconnected(f.player.connection);AbilityInputNetworkServer.LIVE.disconnected(f.player.connection);
            }finally{
                try{for(var replacement:players){f.local.getServer().getPlayerList().remove(replacement);replacement.discard();}}
                finally{f.close();}
            }
        }
    }
    static void respawnPlaced(NativeCharacterTransferGameTest.Fixture f,ServerPlayer replacement,ParticipantPlacement.Directory before){
        f.h.assertTrue(replacement!=f.player&&replacement.connection==f.player.connection&&replacement.getUUID().equals(f.player.getUUID()),"respawn did not replace the same connected player");
        f.h.assertTrue(replacement.level()==f.local&&f.local.getEntity(replacement.getUUID())==replacement&&f.remote.getEntity(replacement.getUUID())==null,"replacement is not indexed in its real respawn dimension");
        var after=f.host.participants();
        f.h.assertValueEqual(after.characters().get(f.holder).domain(),domain(f.local),"respawn character facet stayed in old dimension");
        f.h.assertValueEqual(after.buffs().get(f.holder).domain(),domain(f.local),"respawn buff facet stayed in old dimension");
        f.h.assertTrue(after.characters().get(f.holder).generation()>before.characters().get(f.holder).generation(),"cross-domain respawn reused character lease");
        f.h.assertTrue(after.buffs().get(f.holder).generation()>before.buffs().get(f.holder).generation(),"cross-domain respawn reused buff lease");
        f.h.assertValueEqual(f.host.snapshot().directory().owner(f.holder).lease().domain(),domain(f.local),"respawn resource owner differs from destination");
        near(f.h,f.state(f.local).resources().get(f.key()).value(),.75,"respawn reset or charged already paid energy");
        f.h.assertTrue(!f.state(f.remote).resources().containsKey(f.key()),"respawn left a second resource account");
        f.h.assertTrue(f.state(f.remote).sources().values().stream().noneMatch(source->source.holder().equals(f.holder)),"respawn retained old character source roots");
    }
    static void oldInputRetired(NativeCharacterTransferGameTest.Fixture f,ServerPlayer replacement)throws Exception{
        f.h.assertTrue(replacement.connection.player==replacement&&!replacement.isRemoved()&&replacement.isAlive()&&!replacement.isSpectator(),"replacement is not the connection's eligible player");
        NativeMotionGameTest.acknowledge(replacement);
        replacement.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        f.departure.prepare();f.arrival.prepare();
        f.h.assertTrue(!f.departure.hasAbilityInput(replacement,"test:grenade",1)&&!f.arrival.hasAbilityInput(replacement,"test:grenade",1),"replacement inherited old instance's held ability");
        f.h.assertValueEqual(AbilityInputNetworkServer.LIVE.transferRevision(replacement,"test:grenade"),0L,"old instance gesture retained transfer authority");
        var release=new AbilityInputPayload(2,1,domain(f.local),"test:grenade",AbilityInput.Edge.RELEASE);
        var response=AbilityInputNetworkServer.LIVE.request(replacement,release);
        f.h.assertValueEqual(response.reply(),AbilityInputNetworkServer.Reply.PROCESSED,"replacement stale release was not processed: "+response);
        f.h.assertValueEqual(response.input().orElseThrow().outcome(),AbilityInput.Outcome.NO_PRESS,"replacement released an old instance gesture");
        near(f.h,f.state(f.local).resources().get(f.key()).value(),.75,"stale release spent paid energy after respawn");
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void resourceOnlyMoveAndRestoreLeaveNativeCharacterAndBuffPlacementUntilRealTeleport(GameTestHelper h){
        try(var f=new NativeCharacterTransferGameTest.Fixture(h)){
            placed(f,f.local);var placement=f.host.participants();var buffs=f.state(f.local).buffs();var equipment=f.state(f.local).equipment();
            f.host.transfer(f.holder,f.remote);
            h.assertValueEqual(f.host.participants(),placement,"resource-only transfer changed a participant facet");
            h.assertValueEqual(f.host.snapshot().directory().owner(f.holder).lease().domain(),domain(f.remote),"resource-only transfer did not move its account");
            placed(f,f.local);h.assertValueEqual(f.state(f.local).buffs(),buffs,"resource-only transfer moved local buffs");h.assertValueEqual(f.state(f.local).equipment(),equipment,"resource-only transfer moved equipment");
            h.assertTrue(f.remote.getEntity(f.player.getUUID())==null,"resource-only transfer physically transported player");
            var saved=f.restoreArchive();h.assertValueEqual(saved.resources().placement(),placement,"archive lost independent participant placement");
            f.departure.prepare();f.arrival.prepare();placed(f,f.local);h.assertValueEqual(f.host.participants(),placement,"native re-enrollment rewrote restored placement");
            f.move(f.remote,TeleportTransition.DO_NOTHING);f.located(f.remote);placed(f,f.remote);
            var arrived=f.host.participants();h.assertTrue(arrived.characters().get(f.holder).generation()>placement.characters().get(f.holder).generation(),"real teleport reused character lease");
            h.assertTrue(arrived.buffs().get(f.holder).generation()>placement.buffs().get(f.holder).generation(),"real teleport reused buff lease");f.healthy();f.host.checkpoint();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void emptyBuffPartitionStillReceivesNewLeasesAcrossActualRoundTripAndRestore(GameTestHelper h){
        try(var f=new NativeCharacterTransferGameTest.Fixture(h,emptyBuffProgram())){
            h.assertTrue(f.state(f.local).buffs().instances().isEmpty(),"empty placement fixture unexpectedly granted a buff");placed(f,f.local);var original=f.host.participants();
            f.move(f.remote,TeleportTransition.DO_NOTHING);placed(f,f.remote);var outbound=f.host.participants();
            h.assertTrue(outbound.buffs().get(f.holder).generation()>original.buffs().get(f.holder).generation(),"empty outbound buff facet did not advance");
            f.restoreArchive();h.assertValueEqual(f.host.participants(),outbound,"restore forgot empty buff ownership");
            f.move(f.local,TeleportTransition.DO_NOTHING);placed(f,f.local);var returned=f.host.participants();
            h.assertTrue(returned.characters().get(f.holder).generation()>outbound.characters().get(f.holder).generation(),"character roundtrip reused lease");
            h.assertTrue(returned.buffs().get(f.holder).generation()>outbound.buffs().get(f.holder).generation(),"empty buff roundtrip reused lease");
            h.assertTrue(f.state(f.local).buffs().instances().isEmpty()&&f.state(f.remote).buffs().instances().isEmpty(),"empty facet migration fabricated a buff");f.healthy();f.host.checkpoint();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void carriedWeaponBuffGetsItsOwnPlacementAndFollowsActualPlayerThroughRestore(GameTestHelper h){
        try(var f=new NativeCharacterTransferGameTest.Fixture(h,weaponBuffProgram())){
            var origin=new BuffInstance.Origin(f.holder,"weapon-placement-test",f.weapon("a"),"");
            f.departure.start(new RuleEngine.Signal("test:weapon_buff",new EffectEvent(f.holder,f.holder,origin,Set.of(),Map.of())));f.healthy();
            var original=weaponBuff(f,f.local);long remaining=original.deadline()-f.state(f.local).buffs().timeMicros();var ammo=f.state(f.local).ammunition().get(f.weapon("a"));
            f.move(f.remote,TeleportTransition.DO_NOTHING);f.located(f.remote);placed(f,f.remote);
            var arrived=weaponBuff(f,f.remote);var placement=f.host.participants();var lease=placement.buffs().get(f.weapon("a"));
            h.assertValueEqual(lease.domain(),domain(f.remote),"carried weapon buff facet stayed in departure");
            h.assertTrue(!placement.characters().containsKey(f.weapon("a")),"weapon buff incorrectly became a character facet");
            h.assertValueEqual(arrived.deadline()-f.state(f.remote).buffs().timeMicros(),remaining,"carried weapon buff duration reset");
            h.assertTrue(f.state(f.local).buffs().instances().values().stream().noneMatch(b->b.key().holder().equals(f.weapon("a"))),"departure retained carried weapon buff");
            h.assertValueEqual(f.state(f.remote).ammunition().get(f.weapon("a")),ammo,"weapon buff transport changed ammunition");
            f.restoreArchive();h.assertValueEqual(f.host.participants(),placement,"weapon buff placement changed during restore");h.assertValueEqual(weaponBuff(f,f.remote),arrived,"restore reapplied weapon buff");
            f.move(f.local,TeleportTransition.DO_NOTHING);f.located(f.local);placed(f,f.local);
            var returned=f.host.participants().buffs().get(f.weapon("a"));h.assertValueEqual(returned.domain(),domain(f.local),"carried weapon buff did not return");h.assertTrue(returned.generation()>lease.generation(),"carried weapon buff return reused lease");
            h.assertValueEqual(weaponBuff(f,f.local).deadline()-f.state(f.local).buffs().timeMicros(),remaining,"weapon buff roundtrip reset remaining duration");f.healthy();f.host.checkpoint();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void actualDeathInNetherRespawnsNewLifeInOverworldWithoutResettingPaidStateOrRevivingInput(GameTestHelper h)throws Exception{
        var f=new NativeCharacterTransferGameTest.Fixture(h,NativeInputTransferGameTest.program());
        try{
            f.move(f.remote,TeleportTransition.DO_NOTHING);placed(f,f.remote);NativeMotionGameTest.acknowledge(f.player);f.player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            var before=f.host.participants();String life=MinecraftEntityLife.get(f.player).id();var abilities=PlayerAbilities.get(f.player).snapshot();var actor=PlayerActor.get(f.player).snapshot();
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(1,1,domain(f.remote),"test:grenade",AbilityInput.Edge.PRESS)).input().orElseThrow().outcome(),AbilityInput.Outcome.PRESSED,"Nether old-life ability press failed");
            f.player.hurtServer(f.remote,f.remote.damageSources().genericKill(),1000);h.assertTrue(f.player.isDeadOrDying(),"Nether player did not actually die");
            f.player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            var replacement=f.player.connection.player;replacement.setNoGravity(true);respawnPlaced(f,replacement,before);
            h.assertTrue(!MinecraftEntityLife.get(replacement).id().equals(life),"death respawn kept old entity life");
            h.assertValueEqual(PlayerAbilities.get(replacement).snapshot(),new PlayerAbilities.Stored(abilities.loadout(),abilities.revision()+1),"death respawn did not transfer ability configuration once");h.assertValueEqual(PlayerActor.get(replacement).snapshot(),new PlayerActor.Stored(actor.binding(),actor.revision()+1),"death respawn did not transfer actor configuration once");
            oldInputRetired(f,replacement);
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(replacement,new AbilityInputPayload(3,3,domain(f.local),"test:grenade",AbilityInput.Edge.PRESS)).input().orElseThrow().outcome(),AbilityInput.Outcome.PRESSED,"new life could not press a fresh ability");
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(replacement,new AbilityInputPayload(4,3,domain(f.local),"test:grenade",AbilityInput.Edge.RELEASE)).input().orElseThrow().outcome(),AbilityInput.Outcome.RELEASED,"new life could not release a fresh ability");
            near(h,f.state(f.local).resources().get(f.key()).value(),.5,"fresh respawn ability did not pay exactly once");f.healthy();f.host.checkpoint();
        }finally{closeReplacement(f);}h.succeed();
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void keepAllRespawnReturnsAcrossDimensionsWithSameLifeAndConfigurationButNoOldInput(GameTestHelper h)throws Exception{
        var f=new NativeCharacterTransferGameTest.Fixture(h,NativeInputTransferGameTest.program());
        try{
            f.move(f.remote,TeleportTransition.DO_NOTHING);placed(f,f.remote);NativeMotionGameTest.acknowledge(f.player);f.player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            var before=f.host.participants();String life=MinecraftEntityLife.get(f.player).id();var equipment=PlayerEquipment.get(f.player).snapshot();var abilities=PlayerAbilities.get(f.player).snapshot();var actor=PlayerActor.get(f.player).snapshot();
            int attached=f.cues.size();float health=f.player.getHealth();var ammunition=f.state(f.remote).ammunition();
            h.assertValueEqual(AbilityInputNetworkServer.LIVE.request(f.player,new AbilityInputPayload(1,1,domain(f.remote),"test:grenade",AbilityInput.Edge.PRESS)).input().orElseThrow().outcome(),AbilityInput.Outcome.PRESSED,"keep-all source ability press failed");
            var replacement=f.local.getServer().getPlayerList().respawn(f.player,true,Entity.RemovalReason.CHANGED_DIMENSION);
            // The vanilla respawn caller binds the returned body to the existing packet listener.
            f.player.connection.player=replacement;replacement.setNoGravity(true);respawnPlaced(f,replacement,before);
            h.assertValueEqual(MinecraftEntityLife.get(replacement).id(),life,"keep-all respawn rotated current entity life");near(h,replacement.getHealth(),health,"keep-all respawn reset physical health");
            var current=PlayerEquipment.get(replacement).snapshot();h.assertValueEqual(current.revision(),equipment.revision()+1,"keep-all respawn did not transfer equipment ownership once");h.assertValueEqual(current.drawn(),equipment.drawn(),"keep-all respawn changed drawn slot");
            h.assertValueEqual(current.items().keySet(),equipment.items().keySet(),"keep-all respawn lost equipment slots");for(var entry:equipment.items().entrySet())h.assertTrue(ItemStack.matches(current.items().get(entry.getKey()),entry.getValue()),"keep-all respawn changed item components");
            h.assertValueEqual(PlayerAbilities.get(replacement).snapshot(),new PlayerAbilities.Stored(abilities.loadout(),abilities.revision()+1),"keep-all respawn did not transfer ability configuration once");h.assertValueEqual(PlayerActor.get(replacement).snapshot(),new PlayerActor.Stored(actor.binding(),actor.revision()+1),"keep-all respawn did not transfer actor configuration once");
            oldInputRetired(f,replacement);h.assertValueEqual(f.state(f.local).ammunition(),ammunition,"keep-all respawn reset or charged ammunition");h.assertValueEqual(f.cues.size(),attached,"keep-all respawn replayed source attachment");f.healthy();f.host.checkpoint();
        }finally{closeReplacement(f);}h.succeed();
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void registeredCharacterCannotLeaveItsHostForAnUninstalledDimension(GameTestHelper h){
        try(var f=new NativeCharacterTransferGameTest.Fixture(h)){
            var end=Objects.requireNonNull(f.local.getServer().getLevel(Level.END));
            h.assertTrue(MinecraftEffectRuntime.installed(end).isEmpty(),"End fixture unexpectedly has an installed runtime");
            placed(f,f.local);var before=f.host.checkpoint();var position=f.player.position();
            var equipment=PlayerEquipment.get(f.player).snapshot();var abilities=PlayerAbilities.get(f.player).snapshot();var actor=PlayerActor.get(f.player).snapshot();int[] callbacks={0};
            refused(h,IllegalStateException.class,()->f.move(end,entity->callbacks[0]++));
            placed(f,f.local);f.located(f.local);h.assertValueEqual(f.player.position(),position,"rejected transport changed native position");
            h.assertTrue(end.getEntity(f.player.getUUID())==null,"rejected transport indexed player in uninstalled End");h.assertValueEqual(callbacks[0],0,"rejected transport invoked arrival callback");
            f.containers(equipment,abilities,actor);f.healthy();h.assertValueEqual(f.host.checkpoint(),before,"rejected transport changed placement, resources, or runtime checkpoint");
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void respawnFailureAfterNativeRemovalKeepsUnknownPhysicalResultWithoutPublishingOrRetrying(GameTestHelper h){
        var f=new NativeCharacterTransferGameTest.Fixture(h);
        try{
            f.move(f.remote,TeleportTransition.DO_NOTHING);placed(f,f.remote);f.host.checkpoint();
            var source=f.state(f.remote);var target=f.state(f.local);int attached=f.cues.size();int[] removals={0};
            try(var probe=NativeTransferTestProbe.afterRemoval(f.player,()->{
                removals[0]++;throw new IllegalStateException("Native respawn failed after source removal");
            })){
                refused(h,IllegalStateException.class,()->f.local.getServer().getPlayerList().respawn(f.player,true,Entity.RemovalReason.CHANGED_DIMENSION));
            }
            h.assertValueEqual(removals[0],1,"respawn removal probe never ran");
            h.assertTrue(f.player.isRemoved()&&f.player.level()==f.remote&&f.remote.getEntity(f.player.getUUID())==null&&f.local.getEntity(f.player.getUUID())==null,"failed respawn rolled back removal or inserted a replacement");
            h.assertTrue(f.player.connection.player==f.player,"failed respawn rebound connection to an unconfirmed replacement");
            h.assertValueEqual(f.arrival.state().engine().domain(),source,"failed respawn changed source core before replacement insertion");
            h.assertValueEqual(f.departure.state().engine().domain(),target,"failed respawn published destination core before insertion");
            h.assertTrue(f.departure.failure().isPresent()&&f.arrival.failure().isPresent(),"uncertain respawn did not stop both runtimes");refused(h,IllegalStateException.class,f.host::checkpoint);
            MinecraftEffectRuntime.tick(f.local);MinecraftEffectRuntime.tick(f.remote);
            try(var probe=NativeTransferTestProbe.afterRemoval(f.player,()->removals[0]++)){
                refused(h,IllegalStateException.class,()->f.local.getServer().getPlayerList().respawn(f.player,true,Entity.RemovalReason.CHANGED_DIMENSION));
            }
            h.assertValueEqual(removals[0],1,"failed host retried native respawn removal");h.assertTrue(f.local.getEntity(f.player.getUUID())==null,"failed host inserted a replacement on retry");
            h.assertValueEqual(f.arrival.state().engine().domain(),source,"failed host replayed source respawn work");h.assertValueEqual(f.departure.state().engine().domain(),target,"failed host published target state on retry");h.assertValueEqual(f.cues.size(),attached,"failed respawn replayed source attachment");
        }finally{closeReplacement(f);}h.succeed();
    }
}
