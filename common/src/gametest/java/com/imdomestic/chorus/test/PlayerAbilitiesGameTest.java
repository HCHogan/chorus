package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ActorBindingGameTest.refused;
import static com.imdomestic.chorus.test.PlayerActorGameTest.command;
import static com.imdomestic.chorus.test.PlayerActorGameTest.denied;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
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

public class PlayerAbilitiesGameTest {
    static final String SLOT="test:grenade";
    static AbilityLoadout selection(String name){return name.isEmpty()?AbilityLoadout.EMPTY:new AbilityLoadout(Map.of(SLOT,"test:"+name));}
    static ProjectileGameTest.Harness open(GameTestHelper h)throws Exception{
        var t=new ProjectileGameTest.Harness(h,"projectile",data->{
            var part=JsonParser.parseString(ThreadedSpikeGameTest.json("ability_effects").toString().replace("test:energy","test:ability_energy")).getAsJsonObject();
            var resource=part.getAsJsonArray("resources").get(0).getAsJsonObject();resource.addProperty("capacity",2);resource.addProperty("initial",2);resource.addProperty("base_rate",0);
            part.getAsJsonArray("abilities").forEach(value->value.getAsJsonObject().getAsJsonObject("cost").getAsJsonObject("amount").addProperty("value",1));
            GamblersDodgeGameTest.merge(data,part);AscensionGameTest.version(data);
        },true);t.owner.setGameMode(GameType.SURVIVAL);return t;
    }
    static String owner(ProjectileGameTest.Harness t){return t.owner.getUUID().toString();}
    static void configure(ServerPlayer p,String name){var a=PlayerAbilities.get(p);a.configure(p,selection(name),a.revision());}
    static double energy(MinecraftEffectRuntime runtime,ServerPlayer p){return runtime.state().engine().domain().resources().get(new ResourceState.Key(p.getUUID().toString(),"test:ability_energy")).value();}
    static void stored(ServerPlayer p,PlayerAbilities.Stored value){
        var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,p.level().registryAccess());output.store(PlayerAbilities.SAVE_KEY,PlayerAbilities.Stored.CODEC,value);
        PlayerAbilities.get(p).load(TagValueInput.create(ProblemReporter.DISCARDING,p.level().registryAccess(),output.buildResult()));
    }
    @GameCase(environment="chorus_gametest:player_abilities_commands",maxTicks=50)
    public void commandsCommitCompleteSelectionsPreserveSpentEnergyAndCancelOldHeldInput(GameTestHelper h){ready(h,()->{
        var t=open(h);try{
            var saved=PlayerAbilities.get(t.owner);var observations=new ArrayList<AbilityLoadout>();
            t.beforeWorld=request->{if(request.command() instanceof HealingCommand){var current=PlayerAbilities.get(t.owner).snapshot().loadout();observations.add(current);h.assertValueEqual(t.runtime.state().engine().domain().abilities().getOrDefault(owner(t),AbilityLoadout.EMPTY),current,"physical and domain selection must agree before reactions");}};
            denied(h,t.owner,PermissionSet.NO_PERMISSIONS,"chorus ability choose test:grenade test:a");
            command(t.owner,PermissionSet.ALL_PERMISSIONS,"chorus ability choose test:grenade test:a");near(h,t.owner.getHealth(),11,"actual attach");
            var before=saved.snapshot();var timers=t.runtime.state().engine().domain().timers();configure(t.owner,"a");h.assertValueEqual(saved.snapshot(),before,"same selection consumed revision");h.assertValueEqual(t.runtime.state().engine().domain().timers(),timers,"same selection reset timers");
            refused(h,()->saved.configure(t.owner,selection("b"),0));refused(h,()->saved.configure(t.owner,new AbilityLoadout(Map.of(SLOT,"test:b","test:melee","test:missing")),saved.revision()));h.assertValueEqual(saved.snapshot(),before,"invalid multi-slot request partially committed");
            h.assertValueEqual(t.runtime.useAbility(t.owner,SLOT).outcome(),AbilityUse.Outcome.ACCEPTED,"real paid cast");near(h,energy(t.runtime,t.owner),1,"actual payment");
            h.assertValueEqual(t.runtime.abilityInput(t.owner,SLOT,AbilityInput.Edge.PRESS,1).outcome(),AbilityInput.Outcome.PRESSED,"hold before replacement");
            configure(t.owner,"b");configure(t.owner,"a");h.assertValueEqual(t.runtime.abilityInput(t.owner,SLOT,AbilityInput.Edge.RELEASE,1).outcome(),AbilityInput.Outcome.NO_PRESS,"same-tick selection roundtrip retained stale input");
            command(t.owner,PermissionSet.NO_PERMISSIONS,"chorus ability status");denied(h,t.owner,PermissionSet.NO_PERMISSIONS,"chorus ability clear test:grenade");command(t.owner,PermissionSet.ALL_PERMISSIONS,"chorus ability clear test:grenade");
            near(h,energy(t.runtime,t.owner),1,"selection or clear refilled energy");near(h,t.owner.getHealth(),22,"attach/detach/use order");h.assertTrue(saved.configured()&&saved.snapshot().loadout().slots().isEmpty(),"explicit empty selection must persist");h.assertTrue(observations.contains(selection("b")),"replacement reactions missing");
            t.finish(3,()->{near(h,t.owner.getHealth(),22,"cleared source timer replayed");h.assertTrue(t.runtime.state().engine().domain().abilities().isEmpty(),"automatic sync undid clear");});
        }catch(Exception|Error error){t.close();throw error;}
    });}
    @GameCase(environment="chorus_gametest:player_abilities_nbt",maxTicks=50)
    public void realPlayerNbtRetainsSelectionAndMissingOrMalformedContentCannotEraseIt(GameTestHelper h){ready(h,()->{try(var t=open(h)){
        configure(t.owner,"a");var saved=PlayerAbilities.get(t.owner).snapshot();var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,h.getLevel().registryAccess());t.owner.saveWithoutId(output);t.runtime.close();
        stored(t.owner,new PlayerAbilities.Stored(AbilityLoadout.EMPTY,2));t.owner.load(TagValueInput.create(ProblemReporter.DISCARDING,h.getLevel().registryAccess(),output.buildResult()));h.assertValueEqual(PlayerAbilities.get(t.owner).snapshot(),saved,"actual player NBT lost base selection");
        var empty=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,JsonParser.parseString("{\"version\":\"missing-content\"}")).getOrThrow();
        try(var inactive=PlayerActorGameTest.install(t,empty)){inactive.prepare();h.assertTrue(inactive.state().engine().domain().abilities().isEmpty(),"unknown selection activated");h.assertTrue(PlayerAbilities.get(t.owner).inactiveReason().isPresent(),"missing content diagnostic");h.assertValueEqual(PlayerAbilities.get(t.owner).snapshot(),saved,"missing definitions erased saved selection");}
        try(var active=PlayerActorGameTest.install(t,t.runtime.program())){active.prepare();near(h,t.owner.getHealth(),12,"new runtime attaches exactly once");active.prepare();near(h,t.owner.getHealth(),12,"sync replayed initialization");h.assertTrue(PlayerAbilities.get(t.owner).inactiveReason().isEmpty(),"valid selection stayed inactive");
            var bad=output.buildResult();var raw=bad.getCompoundOrEmpty(PlayerAbilities.SAVE_KEY);raw.putString("revision","broken");bad.put(PlayerAbilities.SAVE_KEY,raw);refused(h,()->PlayerAbilities.get(t.owner).load(TagValueInput.create(ProblemReporter.DISCARDING,h.getLevel().registryAccess(),bad)));h.assertValueEqual(PlayerAbilities.get(t.owner).snapshot(),saved,"malformed NBT erased selection");
        }
    }h.succeed();});}
    @GameCase(environment="chorus_gametest:player_abilities_respawn",maxTicks=50)
    public void actualRespawnRetainsBaseSelectionAndSpentPoolWithoutReplayingAttachment(GameTestHelper h){ready(h,()->{try(var t=open(h)){
        int[] attached={0};t.beforeWorld=request->{if(request.command() instanceof HealingCommand c&&c.amount()==1)attached[0]++;};configure(t.owner,"a");t.runtime.useAbility(t.owner,SLOT);
        var next=ProjectileDestinationLifeGameTest.respawn(t,false);t.runtime.prepare();h.assertValueEqual(PlayerAbilities.get(next).snapshot().loadout(),selection("a"),"respawn lost selection");h.assertTrue(PlayerAbilities.get(t.owner).snapshot().loadout().slots().isEmpty(),"old player retained selection ownership");near(h,energy(t.runtime,next),1,"respawn refilled paid energy");h.assertValueEqual(attached[0],1,"respawn replayed attachment");
        h.assertValueEqual(t.runtime.useAbility(next,SLOT).outcome(),AbilityUse.Outcome.ACCEPTED,"replacement player can cast");near(h,energy(t.runtime,next),0,"replacement paid original pool");h.assertValueEqual(t.runtime.useAbility(next,SLOT).outcome(),AbilityUse.Outcome.INSUFFICIENT_ENERGY,"replacement gained a free cast");
    }h.succeed();});}
    @GameCase(environment="chorus_gametest:player_abilities_restore",maxTicks=50)
    public void checkpointMatchesPlayerSelectionAndRestoresTimerAndSpentPoolWithoutInitialization(GameTestHelper h){ready(h,()->{
        var t=open(h);MinecraftEffectRuntime active=null;try{
            configure(t.owner,"a");t.runtime.useAbility(t.owner,SLOT);var physical=PlayerAbilities.get(t.owner).snapshot();var saved=t.runtime.checkpoint();h.assertValueEqual(saved.abilityOwners(),Set.of(t.owner.getUUID()),"ability owner checkpoint");t.runtime.close();
            stored(t.owner,new PlayerAbilities.Stored(selection("b"),physical.revision()));refused(h,()->PlayerActorGameTest.restore(t,saved));h.assertTrue(MinecraftEffectRuntime.installed(h.getLevel()).isEmpty(),"mismatch installed a runtime");near(h,t.owner.getHealth(),13,"rejected restore healed");
            stored(t.owner,physical);active=PlayerActorGameTest.restore(t,saved);h.assertValueEqual(active.checkpoint(),saved,"exact selection/account/timer checkpoint");near(h,t.owner.getHealth(),13,"restore repeated attachment");near(h,energy(active,t.owner),1,"restore refilled pool");var restored=active;
            h.runAfterDelay(3,()->{try{near(h,t.owner.getHealth(),23,"restored owned timer must run once");near(h,energy(restored,t.owner),1,"timer altered paid balance");h.assertValueEqual(restored.useAbility(t.owner,SLOT).outcome(),AbilityUse.Outcome.ACCEPTED,"remaining paid cast");h.assertValueEqual(restored.useAbility(t.owner,SLOT).outcome(),AbilityUse.Outcome.INSUFFICIENT_ENERGY,"restore granted free casts");h.succeed();}finally{restored.close();t.close();}});
        }catch(Exception|Error error){if(active!=null)active.close();t.close();throw error;}
    });}
    @GameCase(environment="chorus_gametest:player_abilities_unknown",maxTicks=50)
    public void unknownAttachmentRetainsPlayerSelectionAndDomainWithoutRetryOrRefund(GameTestHelper h){ready(h,()->{try(var t=open(h)){
        t.failAfterHealing=true;refused(h,()->configure(t.owner,"a"));near(h,t.owner.getHealth(),11,"already applied attachment");h.assertValueEqual(PlayerAbilities.get(t.owner).snapshot().loadout(),selection("a"),"physical selection rolled back");h.assertValueEqual(t.runtime.state().engine().domain().abilities().get(owner(t)),selection("a"),"domain selection rolled back");
        h.assertTrue(t.runtime.failure().isPresent(),"unknown result acknowledged");refused(h,()->configure(t.owner,"a"));refused(h,t.runtime::checkpoint);MinecraftEffectRuntime.tick(h.getLevel());near(h,t.owner.getHealth(),11,"unknown attachment replayed");
    }h.succeed();});}
    @GameCase(environment="chorus_gametest:player_abilities_late_join",maxTicks=50)
    public void lateLoginMustMatchSavedSelectionBeforeAnyCastOrAutomaticReplacement(GameTestHelper h){ready(h,()->{try(var t=open(h)){
        configure(t.owner,"a");t.runtime.useAbility(t.owner,SLOT);var saved=t.runtime.checkpoint();t.runtime.close();h.getLevel().getServer().getPlayerList().remove(t.owner);
        try(var active=PlayerActorGameTest.restore(t,saved)){
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(t.owner.getUUID(),"abilities-rejoin"),false);var player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());t.entities.add(player);stored(player,new PlayerAbilities.Stored(selection("b"),1));
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);new io.netty.channel.embedded.EmbeddedChannel(connection);h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,player,cookie);player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            active.prepare();h.assertTrue(active.failure().isPresent(),"mismatched player overwrote saved selection");h.assertValueEqual(active.state().engine().domain().abilities().get(owner(t)),selection("a"),"saved selection changed");near(h,energy(active,player),1,"late login reset spent pool");refused(h,()->active.useAbility(player,SLOT));refused(h,active::checkpoint);
        }
    }h.succeed();});}
    @GameCase(environment="chorus_gametest:player_abilities_dimension",maxTicks=80)
    public void returningAfterAnotherDimensionSelectionUsesCurrentPlayerFile(GameTestHelper h){ready(h,()->{try(var t=open(h)){
        configure(t.owner,"a");var origin=t.owner.position();var destination=h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);destination.getChunk(new net.minecraft.core.BlockPos(8,90,8));
        var world=new MinecraftWorldActions(destination,id->destination.getEntity(UUID.fromString(id)) instanceof net.minecraft.world.entity.LivingEntity e?e:null,_ -> destination.damageSources().generic(),(_,_) -> true,_ -> {});
        try(var other=MinecraftEffectRuntime.install(destination,t.runtime.program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),world::apply,MinecraftEffectRuntime::nativeSource)){
            t.owner.teleport(new net.minecraft.world.level.portal.TeleportTransition(destination,new net.minecraft.world.phys.Vec3(8,90,8),net.minecraft.world.phys.Vec3.ZERO,0,0,net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));configure(t.owner,"b");
            var saved=t.runtime.checkpoint();h.assertTrue(!saved.abilityOwners().contains(t.owner.getUUID()),"old dimension still owns current player file validation");t.runtime.close();
            try(var restored=PlayerActorGameTest.restore(t,saved)){
                t.owner.teleport(new net.minecraft.world.level.portal.TeleportTransition(h.getLevel(),origin,net.minecraft.world.phys.Vec3.ZERO,0,0,net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));restored.prepare();h.assertTrue(restored.failure().isEmpty(),"legitimate returning selection treated as corruption");h.assertValueEqual(restored.state().engine().domain().abilities().get(owner(t)),selection("b"),"return ignored current choice");h.assertValueEqual(restored.checkpoint().abilityOwners(),Set.of(t.owner.getUUID()),"return failed to claim validation");h.assertTrue(!other.checkpoint().abilityOwners().contains(t.owner.getUUID()),"old destination retained validation");
            }
        }
    }h.succeed();});}
    @GameCase(environment="chorus_gametest:player_abilities_save_boundary",maxTicks=50)
    public void frozenWorldSaveDetectsChangedPlayerSelectionWithoutReplayingEffects(GameTestHelper h){ready(h,()->{
        var player=NativeMeleeGameTest.player(h);try(var runtime=EffectPrograms.install(h.getLevel(),Identifier.parse("chorus_gametest:checkpoint_restart"),EffectState.Mode.PVE)){
            var loadout=new AbilityLoadout(Map.of(SLOT,"test:restart_ability"));var selection=PlayerAbilities.get(player);selection.configure(player,loadout,0);var physical=selection.snapshot();var before=runtime.state().engine().domain();
            try(var boundary=MinecraftCheckpointBoundary.open(h.getLevel().getServer())){
                h.assertValueEqual(boundary.archive().dimensions().getFirst().abilityOwners(),Set.of(player.getUUID()),"world archive lost ability owner");stored(player,new PlayerAbilities.Stored(AbilityLoadout.EMPTY,physical.revision()+1));refused(h,boundary::requireUnchanged);h.assertValueEqual(runtime.state().engine().domain(),before,"save validation replayed effects");stored(player,physical);
            }
        }finally{player.discard();}h.succeed();
    });}
}
