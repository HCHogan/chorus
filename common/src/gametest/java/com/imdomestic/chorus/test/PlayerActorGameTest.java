package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ActorBindingGameTest.binding;
import static com.imdomestic.chorus.test.ActorBindingGameTest.refused;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.actor.*;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.*;

public class PlayerActorGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t=new ProjectileGameTest.Harness(h,"projectile",data->{GamblersDodgeGameTest.merge(data,ThreadedSpikeGameTest.json("actor_binding"));AscensionGameTest.version(data);},true);
        t.owner.setGameMode(GameType.SURVIVAL);return t;
    }
    static String owner(ProjectileGameTest.Harness t){return t.owner.getUUID().toString();}
    static void configure(ServerPlayer p,ActorBinding binding){var a=PlayerActor.get(p);a.configure(p,Optional.ofNullable(binding),a.revision());}
    static void stored(ServerPlayer p,PlayerActor.Stored value){
        var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,p.level().registryAccess());output.store(PlayerActor.SAVE_KEY,PlayerActor.Stored.CODEC,value);
        PlayerActor.get(p).load(TagValueInput.create(ProblemReporter.DISCARDING,p.level().registryAccess(),output.buildResult()));
    }
    static MinecraftEffectRuntime restore(ProjectileGameTest.Harness t,MinecraftEffectRuntime.Checkpoint saved){return MinecraftEffectRuntime.restore(t.h.getLevel(),t.runtime.program(),saved,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),t.world::apply,MinecraftEffectRuntime::nativeSource);}
    static MinecraftEffectRuntime install(ProjectileGameTest.Harness t,CompiledEffects program){return MinecraftEffectRuntime.install(t.h.getLevel(),program,EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),t.world::apply,MinecraftEffectRuntime::nativeSource);}
    static int command(ServerPlayer p,PermissionSet permission,String text)throws CommandSyntaxException{return p.level().getServer().getCommands().getDispatcher().execute(text,p.createCommandSourceStack().withSuppressedOutput().withPermission(permission));}
    static String setting(ActorBinding value){return "chorus actor set @s "+ActorCodecs.BINDING.encodeStart(JsonOps.INSTANCE,value).getOrThrow();}
    static void denied(GameTestHelper h,ServerPlayer p,PermissionSet permission,String text)throws Exception{try{command(p,permission,text);}catch(CommandSyntaxException expected){return;}h.fail("Actor command should be rejected");}

    @GameCase(environment="chorus_gametest:player_actor_commands",maxTicks=50)
    public void commandsPublishPlayerAndDomainTogetherAndRejectUntrustedOrStaleConfiguration(GameTestHelper h){ready(h,()->{
        var t=open(h);try{
            var actor=PlayerActor.get(t.owner);var observed=new ArrayList<Optional<ActorBinding>>();
            t.beforeWorld=request->{if(request.command() instanceof HealingCommand){var current=actor.snapshot().binding();observed.add(current);h.assertValueEqual(Optional.ofNullable(t.runtime.state().engine().domain().actors().get(owner(t))),current,"physical configuration preceded domain reactions");}};
            denied(h,t.owner,PermissionSet.NO_PERMISSIONS,setting(binding(1)));h.assertTrue(!actor.configured(),"unprivileged command changed player data");
            command(t.owner,PermissionSet.ALL_PERMISSIONS,setting(binding(1)));near(h,t.owner.getHealth(),11,"real attach heal");h.assertValueEqual(actor.revision(),1L,"first revision");
            command(t.owner,PermissionSet.ALL_PERMISSIONS,setting(binding(1)));h.assertValueEqual(actor.revision(),1L,"same request changed revision");
            denied(h,t.owner,PermissionSet.ALL_PERMISSIONS,"chorus actor set @s {\"bundle\":\"test:actor\"}");refused(h,()->actor.configure(t.owner,Optional.of(binding(2)),0));
            command(t.owner,PermissionSet.ALL_PERMISSIONS,setting(binding(2)));near(h,t.owner.getHealth(),14,"replace detach and attach");
            h.assertValueEqual(observed,List.of(Optional.of(binding(1)),Optional.of(binding(2)),Optional.of(binding(2))),"one complete configuration for both replacement reactions");
            command(t.owner,PermissionSet.NO_PERMISSIONS,"chorus actor status");denied(h,t.owner,PermissionSet.NO_PERMISSIONS,"chorus actor clear @s");
            command(t.owner,PermissionSet.ALL_PERMISSIONS,"chorus actor clear @s");near(h,t.owner.getHealth(),16,"actual detach");h.assertTrue(actor.snapshot().binding().isEmpty()&&actor.revision()==3,"clear must persist");
            t.finish(3,()->{near(h,t.owner.getHealth(),16,"cleared owned timer replayed");h.assertTrue(t.runtime.state().engine().domain().actors().isEmpty(),"automatic synchronization undid explicit clear");});
        }catch(Exception|Error error){t.close();throw error;}
    });}
    @GameCase(environment="chorus_gametest:player_actor_nbt",maxTicks=50)
    public void nativePlayerNbtReactivatesOnceOnFreshRuntimeAndMalformedDataCannotEraseConfiguration(GameTestHelper h){ready(h,()->{try(var t=open(h)){
        configure(t.owner,binding(1));var snapshot=PlayerActor.get(t.owner).snapshot();var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,h.getLevel().registryAccess());t.owner.saveWithoutId(output);t.runtime.close();
        stored(t.owner,new PlayerActor.Stored(Optional.empty(),2));t.owner.load(TagValueInput.create(ProblemReporter.DISCARDING,h.getLevel().registryAccess(),output.buildResult()));
        h.assertValueEqual(PlayerActor.get(t.owner).snapshot(),snapshot,"native player NBT lost configuration");near(h,t.owner.getHealth(),11,"saved health before activation");
        try(var active=install(t,t.runtime.program())){active.prepare();near(h,t.owner.getHealth(),12,"fresh runtime did not activate exactly once");active.prepare();near(h,t.owner.getHealth(),12,"duplicate synchronization repeated attach");
            var bad=output.buildResult();var raw=bad.getCompoundOrEmpty(PlayerActor.SAVE_KEY);raw.putString("revision","broken");bad.put(PlayerActor.SAVE_KEY,raw);
            refused(h,()->PlayerActor.get(t.owner).load(TagValueInput.create(ProblemReporter.DISCARDING,h.getLevel().registryAccess(),bad)));
            h.assertValueEqual(PlayerActor.get(t.owner).snapshot(),snapshot,"malformed data silently cleared configuration");
        }
    }h.succeed();});}
    @GameCase(environment="chorus_gametest:player_actor_respawn",maxTicks=50)
    public void actualDeathAndRespawnMoveSavedConfigurationWithoutReplayingRootInitialization(GameTestHelper h){ready(h,()->{try(var t=open(h)){
        int[] attached={0};t.beforeWorld=r->{if(r.command() instanceof HealingCommand command&&command.amount()==1)attached[0]++;};configure(t.owner,binding(1));
        var next=ProjectileDestinationLifeGameTest.respawn(t,false);t.runtime.prepare();
        h.assertValueEqual(PlayerActor.get(next).snapshot().binding(),Optional.of(binding(1)),"respawn lost persistent configuration");h.assertTrue(PlayerActor.get(t.owner).snapshot().binding().isEmpty(),"old player retained ownership");
        h.assertValueEqual(t.runtime.state().engine().domain().actors().get(owner(t)),binding(1),"same owner lost standing rules");h.assertValueEqual(attached[0],1,"respawn replayed initialization");
        configure(next,null);h.assertTrue(t.runtime.state().engine().domain().actors().isEmpty(),"respawned player cannot clear configuration");
    }h.succeed();});}
    @GameCase(environment="chorus_gametest:player_actor_restore",maxTicks=50)
    public void checkpointValidatesPhysicalConfigurationAndRestoresTimersWithoutAttachReplay(GameTestHelper h){ready(h,()->{
        var t=open(h);MinecraftEffectRuntime active=null;try{
            configure(t.owner,binding(1));var physical=PlayerActor.get(t.owner).snapshot();var saved=t.runtime.checkpoint();h.assertValueEqual(saved.actorOwners(),Set.of(t.owner.getUUID()),"managed owner checkpoint");t.runtime.close();
            stored(t.owner,new PlayerActor.Stored(Optional.of(binding(2)),physical.revision()));refused(h,()->restore(t,saved));h.assertTrue(MinecraftEffectRuntime.installed(h.getLevel()).isEmpty(),"mismatched player installed runtime");near(h,t.owner.getHealth(),11,"rejected restore healed");
            stored(t.owner,physical);active=restore(t,saved);h.assertValueEqual(active.checkpoint(),saved,"exact physical/domain restore");near(h,t.owner.getHealth(),11,"restore repeated attachment");var restored=active;
            h.runAfterDelay(3,()->{try{near(h,t.owner.getHealth(),14,"restored timer fired exactly once");configure(t.owner,null);near(h,t.owner.getHealth(),16,"restored persistent clear");h.succeed();}finally{restored.close();t.close();}});
        }catch(Exception|Error error){if(active!=null)active.close();t.close();throw error;}
    });}
    @GameCase(environment="chorus_gametest:player_actor_unknown",maxTicks=50)
    public void unknownNativeAttachmentPreservesPlayerSaveAndDomainWithoutRetryingHealing(GameTestHelper h){ready(h,()->{try(var t=open(h)){
        t.failAfterHealing=true;refused(h,()->configure(t.owner,binding(1)));near(h,t.owner.getHealth(),11,"already applied healing");
        h.assertValueEqual(PlayerActor.get(t.owner).snapshot().binding(),Optional.of(binding(1)),"player configuration was rolled back");h.assertValueEqual(t.runtime.state().engine().domain().actors().get(owner(t)),binding(1),"domain configuration was rolled back");
        h.assertTrue(t.runtime.failure().isPresent(),"unknown result was acknowledged");refused(h,()->configure(t.owner,binding(1)));refused(h,t.runtime::checkpoint);MinecraftEffectRuntime.tick(h.getLevel());near(h,t.owner.getHealth(),11,"failed setup replayed healing");
        var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,h.getLevel().registryAccess());PlayerActor.get(t.owner).save(output);var data=new PlayerActor();data.load(TagValueInput.create(ProblemReporter.DISCARDING,h.getLevel().registryAccess(),output.buildResult()));h.assertValueEqual(data.snapshot(),PlayerActor.get(t.owner).snapshot(),"committed player data not serializable");
    }h.succeed();});}
    @GameCase(environment="chorus_gametest:player_actor_missing",maxTicks=50)
    public void missingContentLeavesSavedConfigurationInactiveUntilItsDefinitionReturns(GameTestHelper h){ready(h,()->{try(var t=open(h)){
        configure(t.owner,binding(1));var saved=PlayerActor.get(t.owner).snapshot();t.runtime.close();
        var data=ThreadedSpikeGameTest.json("actor_binding");var bundles=data.getAsJsonArray("bundles");for(int i=bundles.size()-1;i>=0;i--)if(bundles.get(i).getAsJsonObject().get("id").getAsString().equals("test:actor"))bundles.remove(i);
        var missing=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,data).getOrThrow();try(var inactive=install(t,missing)){inactive.prepare();h.assertTrue(inactive.state().engine().domain().actors().isEmpty(),"missing content became active");h.assertTrue(PlayerActor.get(t.owner).inactiveReason().isPresent(),"missing content had no diagnostic");h.assertValueEqual(PlayerActor.get(t.owner).snapshot(),saved,"missing content erased saved configuration");}
        try(var active=install(t,t.runtime.program())){active.prepare();near(h,t.owner.getHealth(),12,"returned definition attached exactly once");h.assertTrue(PlayerActor.get(t.owner).inactiveReason().isEmpty(),"valid configuration stayed inactive");}
    }h.succeed();});}
    @GameCase(environment="chorus_gametest:player_actor_late_join",maxTicks=50)
    public void playerJoiningAfterRestoreCannotReplaceCheckpointConfigurationBeforeValidation(GameTestHelper h){ready(h,()->{try(var t=open(h)){
        configure(t.owner,binding(1));var saved=t.runtime.checkpoint();t.runtime.close();h.getLevel().getServer().getPlayerList().remove(t.owner);
        try(var active=restore(t,saved)){
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(t.owner.getUUID(),"actor-rejoin"),false);
            var player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());t.entities.add(player);stored(player,new PlayerActor.Stored(Optional.of(binding(2)),1));
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);new io.netty.channel.embedded.EmbeddedChannel(connection);h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,player,cookie);
            h.assertValueEqual(h.getLevel().getServer().getPlayerList().getPlayers().stream().filter(p->p.getUUID().equals(player.getUUID())).count(),1L,"rejoin fixture duplicated the online player");
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());active.prepare();
            h.assertTrue(active.failure().isPresent(),"joining mismatched player silently replaced saved configuration");h.assertValueEqual(active.state().engine().domain().actors().get(owner(t)),binding(1),"checkpoint root was overwritten");refused(h,active::checkpoint);
        }
    }h.succeed();});}
    @GameCase(environment="chorus_gametest:player_actor_dimension",maxTicks=80)
    public void crossDimensionReturnAfterRestoreAcceptsUpdatedConfigurationAndKeepsHistoricalRules(GameTestHelper h){ready(h,()->{try(var t=open(h)){
        configure(t.owner,binding(1));var origin=t.owner.position();var destination=h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);destination.getChunk(new net.minecraft.core.BlockPos(8,90,8));
        var world=new MinecraftWorldActions(destination,id->destination.getEntity(UUID.fromString(id)) instanceof net.minecraft.world.entity.LivingEntity e?e:null,_ -> destination.damageSources().generic(),(_,_) -> true,_ -> {});
        try(var other=MinecraftEffectRuntime.install(destination,t.runtime.program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),world::apply,MinecraftEffectRuntime::nativeSource)){
            t.owner.teleport(new net.minecraft.world.level.portal.TeleportTransition(destination,new net.minecraft.world.phys.Vec3(8,90,8),net.minecraft.world.phys.Vec3.ZERO,0,0,net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            h.assertTrue(t.owner.level()==destination,"native dimension transfer failed");configure(t.owner,binding(2));
            var saved=t.runtime.checkpoint();h.assertTrue(!saved.actorOwners().contains(t.owner.getUUID()),"old dimension still claims the current player file");
            h.assertValueEqual(t.runtime.state().engine().domain().actors().get(owner(t)),binding(1),"transfer erased historical standing rules");t.runtime.close();
            try(var restored=restore(t,saved)){
                t.owner.teleport(new net.minecraft.world.level.portal.TeleportTransition(h.getLevel(),origin,net.minecraft.world.phys.Vec3.ZERO,0,0,net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));restored.prepare();
                h.assertTrue(restored.failure().isEmpty(),"legitimate returning configuration treated as checkpoint corruption");h.assertValueEqual(restored.state().engine().domain().actors().get(owner(t)),binding(2),"return did not use current player configuration");
                h.assertValueEqual(restored.checkpoint().actorOwners(),Set.of(t.owner.getUUID()),"return did not reclaim player-file ownership");
                h.assertTrue(!other.checkpoint().actorOwners().contains(t.owner.getUUID()),"destination retained player-file ownership after return");
                h.assertValueEqual(other.state().engine().domain().actors().get(owner(t)),binding(2),"return erased destination's historical rules");
            }
        }
    }h.succeed();});}
    @GameCase(environment="chorus_gametest:player_actor_save_boundary",maxTicks=50)
    public void frozenWorldSaveChecksPersistentPlayerConfigurationAsWellAsDomainState(GameTestHelper h){ready(h,()->{
        var player=NativeMeleeGameTest.player(h);try(var runtime=EffectPrograms.install(h.getLevel(),Identifier.parse("chorus_gametest:checkpoint_restart"),EffectState.Mode.PVE)){
            configure(player,new ActorBinding("test:restart_actor"));var physical=PlayerActor.get(player).snapshot();var before=runtime.state().engine().domain();
            try(var boundary=MinecraftCheckpointBoundary.open(h.getLevel().getServer())){
                h.assertValueEqual(boundary.archive().dimensions().getFirst().actorOwners(),Set.of(player.getUUID()),"world archive lost managed actor identity");
                stored(player,new PlayerActor.Stored(Optional.empty(),physical.revision()+1));refused(h,boundary::requireUnchanged);h.assertValueEqual(runtime.state().engine().domain(),before,"save validation replayed actor reactions");stored(player,physical);
            }
        }finally{player.discard();}h.succeed();
    });}
}
