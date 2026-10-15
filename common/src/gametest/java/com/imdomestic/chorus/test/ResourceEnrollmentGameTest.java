package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.object;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.refused;
import static com.imdomestic.chorus.test.ResourceRuntimeGameTest.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.actor.ActorBinding;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Real player entry points must work without a host-side claim or an implicit energy reset. */
public class ResourceEnrollmentGameTest {
    static final AbilityLoadout SELECTED=new AbilityLoadout(Map.of("test:grenade","test:a"));
    static JsonObject initialized() {
        var data=abilities();var actions=data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject();
        var next=new JsonArray();next.add(object("{\"type\":\"chorus:initialize_resource\",\"resource\":\"test:energy\"}"));next.addAll(actions.getAsJsonArray("do"));actions.add("do",next);return data;
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void firstSelectionAutomaticallyEnrollsAndRepeatedPrepareOrRestoreNeverRefills(GameTestHelper h) {
        var player=NativeMeleeGameTest.player(h);
        try(var f=new Fixture(h,initialized(),player,false,false)) {
            h.assertTrue(f.host.snapshot().directory().holders().isEmpty(),"installation inferred unobserved owners");
            PlayerAbilities.get(player).configure(player,SELECTED,0);
            h.assertValueEqual(f.host.snapshot().directory().owner(id(player)).lease().domain(),domain(f.local),"initial native owner");
            near(h,player.getHealth(),3,"one initialization heal");f.runtime.useAbility(player,"test:grenade");
            var lease=f.host.snapshot().directory().owner(id(player)).lease();f.runtime.prepare();f.runtime.prepare();f.settled();
            near(h,f.host.snapshot().directory().owner(f.key()).account("test:energy").value(),.75,"repeat prepare refilled energy");
            h.assertValueEqual(f.host.snapshot().directory().owner(id(player)).lease(),lease,"repeat prepare replaced lease");
            var saved=f.host.checkpoint();f.host.close();f.host=MinecraftResourceRuntime.restore(f.local.getServer(),saved,f.policies());f.runtime=f.host.runtime(f.local);
            f.runtime.prepare();h.assertValueEqual(f.host.checkpoint(),saved,"restored player re-enrolled or replayed sources");h.assertValueEqual(f.heals,2,"restore repeated world writes");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void firstActorConfigurationEnrollsBeforeItsResourceAndHealingReactions(GameTestHelper h) {
        var player=NativeMeleeGameTest.player(h);
        try(var f=new Fixture(h,initialized(),player,false,false)) {
            PlayerActor.get(player).configure(player,Optional.of(new ActorBinding("test:passive")),0);f.settled();
            near(h,f.host.snapshot().directory().owner(f.key()).account("test:energy").value(),1,"actor failed to initialize routed account");
            near(h,player.getHealth(),3,"actor initialization heal");var saved=f.host.checkpoint();f.runtime.prepare();
            h.assertValueEqual(f.host.checkpoint(),saved,"actor refresh repeated attachment");h.assertValueEqual(f.heals,1,"actor initialized twice");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void firstPhysicalEquipmentSwapEnrollsBeforeAttachedResourceInitialization(GameTestHelper h) {
        var data=ThreadedSpikeGameTest.json("equipment");data.add("resources",initialized().get("resources"));
        for(var value:data.getAsJsonArray("bundles"))if(value.getAsJsonObject().get("id").getAsString().equals("test:gear_passive")) {
            var bundle=value.getAsJsonObject();var rules=bundle.has("rules")?bundle.getAsJsonArray("rules"):new JsonArray();
            rules.add(object("{\"id\":\"enroll_init\",\"on\":\"chorus:source_attached\",\"if\":{\"type\":\"chorus:own_source\"},\"do\":[{\"type\":\"chorus:initialize_resource\",\"resource\":\"test:energy\"}]}"));bundle.add("rules",rules);
        }
        var player=NativeMeleeGameTest.player(h);
        try(var f=new Fixture(h,data,player,false,false)) {
            var gear=new Loadout.Gear("enrollment/rifle","test:rifle",Map.of("perk","normal"));var item=new ItemStack(Items.DIAMOND_SWORD);item.set(ChorusComponents.EQUIPMENT.get(),gear);player.getInventory().setItem(0,item);
            var physical=PlayerEquipment.get(player);physical.swap(player,"test:weapon_a",0,0);f.settled();
            h.assertTrue(player.getInventory().getItem(0).isEmpty(),"equipment did not physically move");near(h,f.host.snapshot().directory().owner(f.key()).account("test:energy").value(),1,"equipment routed initialization");
            var lease=f.host.snapshot().directory().owner(id(player)).lease();physical.swap(player,"test:weapon_a",0,physical.revision());
            h.assertValueEqual(f.host.snapshot().directory().owner(id(player)).lease(),lease,"unequip discarded resource ownership");f.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void loadedPlayerSelectionIsEnrolledBeforeAutomaticSourceAttachment(GameTestHelper h) {
        var player=NativeMeleeGameTest.player(h);PlayerAbilitiesGameTest.stored(player,new PlayerAbilities.Stored(SELECTED,1));
        try(var f=new Fixture(h,initialized(),player,false,false)) {
            f.runtime.prepare();f.settled();near(h,player.getHealth(),3,"loaded selection initialized once");
            h.assertValueEqual(f.runtime.useAbility(player,"test:grenade").outcome(),AbilityUse.Outcome.ACCEPTED,"loaded selection cannot spend");
            var saved=f.host.checkpoint();f.runtime.prepare();h.assertValueEqual(f.host.checkpoint(),saved,"loaded selection reinitialized");
            near(h,f.host.snapshot().directory().owner(f.key()).account("test:energy").value(),.75,"loaded selection refilled");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void playerJoiningAnAlreadyInstalledGroupIsAdmittedBeforeLoadedSelectionRuns(GameTestHelper h) {
        try(var f=new Fixture(h,initialized(),h.spawnWithNoFreeWill(net.minecraft.world.entity.EntityTypes.COW,2,3,2),false,false)) {
            var player=NativeMeleeGameTest.player(h);
            try {
                player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);player.setHealth(2);
                PlayerAbilitiesGameTest.stored(player,new PlayerAbilities.Stored(SELECTED,1));f.runtime.prepare();f.settled();
                near(h,player.getHealth(),3,"late joining selection initialized once");
                h.assertValueEqual(f.host.snapshot().directory().owner(id(player)).lease().domain(),domain(f.local),"late joining owner missing");
                h.assertValueEqual(f.runtime.useAbility(player,"test:grenade").outcome(),AbilityUse.Outcome.ACCEPTED,"late joining paid input");
                near(h,f.host.snapshot().directory().owner(id(player)).account("test:energy").value(),.75,"late joining balance");
            }finally{f.local.getServer().getPlayerList().remove(player);player.discard();}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void unindexedPlayerCannotCreateAResourceOwnerOrCommitConfiguration(GameTestHelper h) {
        var server=h.getLevel().getServer();var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(),"unindexed"),false);
        var player=new ServerPlayer(server,h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        try(var f=new Fixture(h,initialized(),player,false,false)) {
            f.runtime.prepare();var before=f.host.snapshot().directory();
            h.assertTrue(!before.holders().containsKey(id(player)),"prepare enrolled an unindexed player");
            refused(h,IllegalArgumentException.class,()->PlayerAbilities.get(player).configure(player,SELECTED,0));
            refused(h,IllegalArgumentException.class,()->PlayerActor.get(player).configure(player,Optional.of(new ActorBinding("test:passive")),0));
            refused(h,IllegalArgumentException.class,()->f.runtime.trackEquipment(player));
            h.assertValueEqual(f.host.snapshot().directory(),before,"unindexed player changed resource ownership");
            h.assertTrue(!PlayerAbilities.get(player).configured()&&!PlayerActor.get(player).configured(),"rejected entry changed native metadata");h.assertValueEqual(f.heals,0,"unindexed entry ran actions");f.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void physicalArrivalPreservesAnExistingEmptyRemoteOwnerAndRoutesLaterInitializationThere(GameTestHelper h) {
        var data=initialized();data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").remove(1);
        var player=NativeMeleeGameTest.player(h);
        try(var f=new Fixture(h,data,player,false,false)) {
            f.runtime.prepare();var owner=f.host.snapshot().directory().owner(id(player));h.assertTrue(owner.accounts().isEmpty(),"empty admission created energy");
            f.remote.getChunk(new net.minecraft.core.BlockPos(8,90,8));player.teleport(new TeleportTransition(f.remote,new Vec3(8,90,8),Vec3.ZERO,0,0,TeleportTransition.DO_NOTHING));
            var remote=f.host.runtime(f.remote);remote.prepare();h.assertValueEqual(f.host.snapshot().directory().owner(id(player)),owner,"physical arrival stole old lease");
            remote.bind(new EffectSource("enrollment/remote","test:passive",id(player),new BuffInstance.Origin(id(player),"entry","",""),Set.of()));
            f.host.drain(16);h.assertTrue(remote.failure().isEmpty(),"remote initialization failed");
            near(h,f.runtime.state().engine().domain().resources().get(f.key()).value(),1,"initialization ignored existing empty authority");
            h.assertTrue(remote.state().engine().domain().resources().isEmpty(),"arrival duplicated energy");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void unknownFirstAttachmentRetainsAutoEnrollmentAndPhysicalWriteWithoutReplay(GameTestHelper h) {
        var player=NativeMeleeGameTest.player(h);
        try(var f=new Fixture(h,initialized(),player,false,false)) {
            f.loseReceipt=true;refused(h,IllegalStateException.class,()->PlayerAbilities.get(player).configure(player,SELECTED,0));
            near(h,player.getHealth(),3,"unknown attachment lost physical healing");h.assertValueEqual(PlayerAbilities.get(player).snapshot().loadout(),SELECTED,"unknown attachment lost native metadata");
            h.assertTrue(f.runtime.failure().isPresent(),"unknown world write acknowledged");refused(h,IllegalStateException.class,f.host::checkpoint);
            MinecraftEffectRuntime.tick(f.local);h.assertValueEqual(f.heals,1,"unknown attachment replayed");
            // Closing does not recover or erase the unresolved operation; the failed session remains evidence.
            h.assertTrue(f.runtime.state().engine().pending().isPresent(),"unknown operation lost pending evidence");
        }h.succeed();
    }
}
