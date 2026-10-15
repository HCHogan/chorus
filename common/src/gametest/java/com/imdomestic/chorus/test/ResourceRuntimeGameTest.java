package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;

/** Installed native runtimes, actual world writes, and explicit group lifecycle. */
public class ResourceRuntimeGameTest {
    static String id(LivingEntity entity){return entity.getUUID().toString();}
    static String domain(ServerLevel level){return level.dimension().identifier().toString();}
    static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final ServerLevel local,remote;final LivingEntity actor;final CompiledEffects program;
        final net.minecraft.world.damagesource.DamageSource damageSource;final MinecraftWorldActions world;final MinecraftEffectRuntime.NativeSource nativeSource;
        MinecraftResourceRuntime host;MinecraftEffectRuntime runtime;boolean loseReceipt;int heals;final List<DamageReceipt> hits=new ArrayList<>();
        Consumer<RuleEngine.WorldRequest> beforeWorld=_ -> {};
        Fixture(GameTestHelper h,JsonObject data,LivingEntity actor,boolean initial){
            this(h,data,actor,initial,true);
        }
        Fixture(GameTestHelper h,JsonObject data,LivingEntity actor,boolean initial,boolean claim){
            this.h=h;this.local=h.getLevel();this.remote=Objects.requireNonNull(local.getServer().getLevel(Level.NETHER));this.actor=actor;program=compile(data);
            actor.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);actor.setHealth(2);
            damageSource=local.damageSources().generic();
            nativeSource=(target,source,amount)->source==damageSource?new DamageCommand(id(target),source("query",actor).origin(),amount,"test:hit",Set.of(),Set.of(),false,Optional.of("test:health")):MinecraftEffectRuntime.nativeSource(target,source,amount);
            world=new MinecraftWorldActions(local,key->local.getEntity(UUID.fromString(key)) instanceof LivingEntity e?e:null,_ -> damageSource,(_,_) -> true,_ -> {});
            host=new MinecraftResourceRuntime(local.getServer());
            try{
                var state=initial?EffectState.empty().withSource(source("query",actor)).withResource(new ResourceState(key(),.25,1,0)):EffectState.empty();
                runtime=host.install(local,program,state,clock(),this::world,nativeSource);
                host.install(remote,program,EffectState.empty(),clock(),r->{throw new IllegalStateException("Unexpected remote world command: "+r.command());},MinecraftEffectRuntime::nativeSource);
                if(claim)host.claim(id(actor),local);
            }catch(RuntimeException|Error failure){host.close();actor.discard();throw failure;}
        }
        ResourceState.Key key(){return new ResourceState.Key(id(actor),"test:energy");}
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request){
            beforeWorld.accept(request);var result=world.apply(request);if(result instanceof DamageReceipt damage)hits.add(damage);
            if(request.command() instanceof HealingCommand){heals++;if(loseReceipt)throw new IllegalStateException("Injected loss after physical healing");}return result;
        }
        void event(String name,LivingEntity victim){runtime.start(new RuleEngine.Signal(name,new EffectEvent(id(actor),id(victim),source("query",actor).origin(),Set.of(),Map.of())));}
        void transfer(){host.transfer(id(actor),remote);}
        void settled(){h.assertTrue(runtime.failure().isEmpty()&&runtime.state().idle(),"Native resource runtime failed: "+runtime.failure());}
        Map<String,MinecraftResourceRuntime.RestoreBinding> policies(){return Map.of(
            domain(local),new MinecraftResourceRuntime.RestoreBinding(local,program,clock(),this::world,nativeSource),
            domain(remote),new MinecraftResourceRuntime.RestoreBinding(remote,program,clock(),_ -> RuleEngine.Empty.INSTANCE,MinecraftEffectRuntime::nativeSource));}
        @Override public void close(){try{host.close();}finally{if(actor instanceof ServerPlayer p&&p.connection!=null)local.getServer().getPlayerList().remove(p);actor.discard();}}
    }
    static Fixture projection(GameTestHelper h){
        var data=data();data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").add(object("""
            {"id":"hit","on":"test:hit","do":[{"type":"chorus:damage","amount":{"type":"chorus:constant","value":10,"unit":"damage"},"damage_type":"test:hit","scaling_profile":"test:health"}]}
            """));return new Fixture(h,data,h.spawnWithNoFreeWill(EntityTypes.COW,2,3,2),true);
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void installedRuntimeReadsRemoteAccountsBeforePhysicalHealingAndNativeDamage(GameTestHelper h){
        try(var f=projection(h)){
            near(h,f.actor.getMaxHealth(),110,"initial projection");f.transfer();f.host.grant(f.key(),.25,.25);f.runtime.prepare();
            near(h,f.actor.getMaxHealth(),120,"remote projection");h.assertTrue(f.runtime.state().engine().domain().resources().isEmpty(),"recreated local resource");
            var victim=h.spawnWithNoFreeWill(EntityTypes.COW,4,3,2);victim.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);victim.setHealth(100);
            try{
                f.event("test:hit",victim);var receipt=f.hits.getFirst();
                near(h,receipt.healthLoss(),30,"remote outgoing profile applied once to real damage");near(h,victim.getHealth(),70,"physical health loss");
                victim.damageCooldownTime=0;h.assertTrue(victim.hurtServer(f.local,f.damageSource,10),"ordinary native hit rejected");near(h,victim.getHealth(),40,"ordinary native hit reads remote resource once");
                f.event("test:heal",f.actor);near(h,f.actor.getMaxHealth(),140,"grant precedes projection inside active world request");near(h,f.actor.getHealth(),140,"healing used updated remote capacity");h.assertValueEqual(f.heals,1,"one physical healing");f.settled();
            }finally{victim.discard();}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void attachmentInitializesResourcesBeforeAnyDependentNativeProjection(GameTestHelper h){
        var data=data();data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").add(object("""
            {"id":"initialize","on":"chorus:source_attached","if":{"type":"chorus:own_source"},"do":[{"type":"chorus:initialize_resource","resource":"test:energy"}]}
            """));
        try(var f=new Fixture(h,data,h.spawnWithNoFreeWill(EntityTypes.COW,2,3,2),false)){
            f.transfer();f.runtime.bind(source("query",f.actor));f.settled();near(h,f.actor.getMaxHealth(),110,"resource initialized before native query");
            h.assertTrue(f.runtime.state().engine().domain().resources().isEmpty(),"initialization used caller instead of owner");
            near(h,f.host.snapshot().domains().get(domain(f.remote)).resources().get(f.key()).value(),.25,"remote initial balance");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void lostWorldReceiptPreservesRemoteGrantAndActualHealingWithoutRetry(GameTestHelper h){
        try(var f=projection(h)){
            f.transfer();f.loseReceipt=true;refused(h,IllegalStateException.class,()->f.event("test:heal",f.actor));
            near(h,f.actor.getHealth(),130,"unknown receipt rolled back physical heal");near(h,f.actor.getMaxHealth(),130,"unknown receipt rolled back projection");
            near(h,f.host.runtime(f.remote).state().engine().domain().resources().get(f.key()).value(),.75,"unknown receipt rolled back remote grant");
            h.assertTrue(f.runtime.failure().isPresent(),"unknown operation acknowledged");refused(h,IllegalStateException.class,f.host::checkpoint);
            refused(h,IllegalStateException.class,()->f.event("test:heal",f.actor));MinecraftEffectRuntime.tick(f.local);h.assertValueEqual(f.heals,1,"unknown write retried");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void groupCheckpointRestoresBothDomainsAndAutomaticEntityLoadProjectionWithoutReplay(GameTestHelper h){
        try(var f=projection(h)){
            f.transfer();f.event("test:heal",f.actor);f.host.drain(16);var saved=f.host.checkpoint();
            var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,f.local.registryAccess());f.actor.saveWithoutId(output);
            f.host.close();near(h,f.actor.getMaxHealth(),100,"group close retained native projection");
            f.host=MinecraftResourceRuntime.restore(f.local.getServer(),saved,f.policies());f.runtime=f.host.runtime(f.local);
            h.assertValueEqual(f.host.checkpoint(),saved,"joint native checkpoint changed");h.assertValueEqual(f.heals,1,"restore replayed healing");
            var replacement=Objects.requireNonNull(EntityTypes.COW.create(f.local,EntitySpawnReason.LOAD));
            try{replacement.load(TagValueInput.create(ProblemReporter.DISCARDING,f.local.registryAccess(),output.buildResult()));
                near(h,replacement.getMaxHealth(),130,"automatic NBT hook missed remote resources");near(h,replacement.getHealth(),130,"saved health clamped before native projection");
                h.assertTrue(f.local.getEntity(replacement.getUUID())!=replacement,"replacement unexpectedly indexed");
            }finally{replacement.discard();}
            h.assertValueEqual(f.heals,1,"loading replayed actions");
        }h.succeed();
    }
    static JsonObject abilities(){return object("""
        {"version":"native-resource-abilities","resources":[{"id":"test:energy","capacity":1,"initial":1}],
         "abilities":[{"id":"test:a","slot":"test:grenade","cost":{"resource":"test:energy","amount":{"type":"chorus:constant","value":0.25,"unit":"charge_fraction"}},
           "effects":{"passive":{"bundle":"test:passive"}},"on_use":[{"type":"chorus:heal","amount":{"type":"chorus:constant","value":2,"unit":"damage"}}]}],
         "bundles":[{"id":"test:passive","rules":[{"id":"attach","on":"chorus:source_attached","if":{"type":"chorus:own_source"},"do":[{"type":"chorus:heal","amount":{"type":"chorus:constant","value":1,"unit":"damage"}}]}]}]}
        """);}
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void playerSelectionPublishesBeforeReactionsAndPaidInputUsesOneLocalOwner(GameTestHelper h){
        var player=NativeMeleeGameTest.player(h);
        try(var f=new Fixture(h,abilities(),player,false)){
            var selected=new AbilityLoadout(Map.of("test:grenade","test:a"));var physical=PlayerAbilities.get(player);
            f.beforeWorld=_ -> h.assertValueEqual(f.runtime.state().engine().domain().abilities().get(id(player)),physical.snapshot().loadout(),"physical and domain selections differ during reaction");
            physical.configure(player,selected,0);near(h,player.getHealth(),3,"one real attachment");
            h.assertValueEqual(f.runtime.useAbility(player,"test:grenade").outcome(),AbilityUse.Outcome.ACCEPTED,"native paid cast");near(h,player.getHealth(),5,"one cast heal");
            near(h,f.runtime.state().engine().domain().resources().get(f.key()).value(),.75,"one native cost");
            var stored=physical.snapshot();f.transfer();refused(h,IllegalStateException.class,()->f.runtime.useAbility(player,"test:grenade"));
            refused(h,IllegalStateException.class,()->physical.configure(player,AbilityLoadout.EMPTY,physical.revision()));h.assertValueEqual(physical.snapshot(),stored,"rejected remote selection changed player save");
            f.host.transfer(id(player),f.local);physical.configure(player,AbilityLoadout.EMPTY,physical.revision());f.settled();h.assertValueEqual(f.heals,2,"selection replayed effects");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void unknownAttachmentKeepsCommittedPlayerSelectionAndStopsReplay(GameTestHelper h){
        var player=NativeMeleeGameTest.player(h);
        try(var f=new Fixture(h,abilities(),player,false)){
            var selected=new AbilityLoadout(Map.of("test:grenade","test:a"));f.loseReceipt=true;
            refused(h,IllegalStateException.class,()->PlayerAbilities.get(player).configure(player,selected,0));near(h,player.getHealth(),3,"actual attachment heal retained");
            h.assertValueEqual(PlayerAbilities.get(player).snapshot().loadout(),selected,"player save rolled back");h.assertValueEqual(f.runtime.state().engine().domain().abilities().get(id(player)),selected,"domain selection rolled back");
            refused(h,IllegalStateException.class,f.host::checkpoint);MinecraftEffectRuntime.tick(f.local);h.assertValueEqual(f.heals,1,"attachment replayed");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void realEquipmentTransferCommitsContainerAndDomainBeforeAttachedEffects(GameTestHelper h)throws Exception{
        var data=ThreadedSpikeGameTest.json("equipment");var player=NativeMeleeGameTest.player(h);
        try(var f=new Fixture(h,data,player,false)){
            var gear=new Loadout.Gear("resource-runtime/rifle","test:rifle",Map.of("perk","normal"));var item=new ItemStack(Items.DIAMOND_SWORD);item.set(ChorusComponents.EQUIPMENT.get(),gear);player.getInventory().setItem(0,item);
            var physical=PlayerEquipment.get(player);f.beforeWorld=_ -> h.assertValueEqual(f.runtime.state().engine().domain().equipment().getOrDefault(id(player),Loadout.EMPTY),new Loadout(physical.snapshot().items().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->e.getValue().get(ChorusComponents.EQUIPMENT.get()))),physical.snapshot().drawn()),"container differs during reaction");
            physical.swap(player,"test:weapon_a",0,physical.revision());h.assertTrue(player.getInventory().getItem(0).isEmpty(),"physical ownership not transferred");
            h.assertValueEqual(physical.item("test:weapon_a").get(ChorusComponents.EQUIPMENT.get()),gear,"wrong equipped item");f.settled();
            physical.swap(player,"test:weapon_a",0,physical.revision());h.assertValueEqual(player.getInventory().getItem(0).get(ChorusComponents.EQUIPMENT.get()),gear,"unequip lost item");f.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void explicitGroupRejectsPartialCloseAndDefaultSaveAndFailedAdmissionLeavesExistingHostHealthy(GameTestHelper h){
        try(var f=projection(h)){
            refused(h,IllegalStateException.class,f.runtime::close);refused(h,IllegalArgumentException.class,()->MinecraftCheckpointBoundary.open(f.local.getServer()));
            refused(h,IllegalStateException.class,()->f.host.install(f.local,f.program,EffectState.empty(),clock(),f::world,f.nativeSource));
            var other=Objects.requireNonNull(f.local.getServer().getLevel(Level.END));
            refused(h,IllegalStateException.class,()->f.host.install(other,f.program,EffectState.empty().withResource(new ResourceState(f.key(),.25,1,0)),clock(),f::world,f.nativeSource));
            h.assertTrue(MinecraftEffectRuntime.installed(other).isEmpty(),"failed admission published runtime");f.event("test:heal",f.actor);f.settled();
            f.host.close();h.assertTrue(MinecraftEffectRuntime.installed(f.local).isEmpty()&&MinecraftEffectRuntime.installed(f.remote).isEmpty(),"group close retained member");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void failedRestoreCleansEveryMemberAndFailedAttachmentRequiresClosingTheWholeGroup(GameTestHelper h){
        try(var f=projection(h)){
            f.transfer();var saved=f.host.checkpoint();f.host.close();
            var dimensions=new TreeMap<>(saved.dimensions());var old=dimensions.get(domain(f.local));
            dimensions.put(domain(f.local),new MinecraftEffectRuntime.Checkpoint(old.dimension(),old.gameTime()+1,old.effects(),old.equipmentOwners(),old.actorOwners(),old.abilityOwners()));
            var mismatched=new MinecraftResourceRuntime.Checkpoint(saved.resources(),dimensions);
            refused(h,IllegalArgumentException.class,()->MinecraftResourceRuntime.restore(f.local.getServer(),mismatched,f.policies()));
            h.assertTrue(MinecraftEffectRuntime.installed(f.local).isEmpty()&&MinecraftEffectRuntime.installed(f.remote).isEmpty(),"failed restore leaked member");
            f.host=MinecraftResourceRuntime.restore(f.local.getServer(),saved,f.policies());near(h,f.actor.getMaxHealth(),110,"valid restore unavailable after rejection");f.host.close();
            f.host=new MinecraftResourceRuntime(f.local.getServer());
            refused(h,IllegalArgumentException.class,()->f.host.install(f.local,f.program,EffectState.empty().withSource(source("query",f.actor)),clock(),f::world,f.nativeSource));
            refused(h,IllegalStateException.class,f.host::checkpoint);refused(h,IllegalStateException.class,()->f.host.runtime(f.local));
            f.host.close();h.assertTrue(MinecraftEffectRuntime.installed(f.local).isEmpty(),"failed attachment leaked registration");near(h,f.actor.getMaxHealth(),100,"failed group cleanup retained projection");
        }h.succeed();
    }
}
