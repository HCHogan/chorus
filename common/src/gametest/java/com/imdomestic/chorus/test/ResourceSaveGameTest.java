package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.refused;
import static com.imdomestic.chorus.test.ResourceRuntimeGameTest.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;

/** Native startup staging and joint catalogue/world save boundaries. */
public class ResourceSaveGameTest {
    static Map<String,MinecraftResourceRuntime.RestorePolicy> policies(ResourceRuntimeGameTest.Fixture f){
        var result=new TreeMap<String,MinecraftResourceRuntime.RestorePolicy>();
        f.policies().forEach((domain,p)->result.put(domain,new MinecraftResourceRuntime.RestorePolicy(p.program(),p.clock(),_ -> p.world(),p.source())));return result;
    }
    @GameCase(environment="chorus_gametest:resource_save")
    public void entityLoadingReadsAllRestoredDomainsBeforeTheirNativeLevelsAreConnected(GameTestHelper h){
        try(var f=projection(h)){
            f.transfer();f.event("test:heal",f.actor);f.host.drain(16);var saved=f.host.checkpoint();
            var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,f.local.registryAccess());f.actor.saveWithoutId(output);f.host.close();
            f.host=MinecraftResourceRuntime.prepareRestore(f.local.getServer(),saved,policies(f));f.runtime=f.host.restoreLevel(f.local);
            h.assertTrue(MinecraftEffectRuntime.installed(f.remote).isEmpty(),"remote runtime connected too early");
            var before=f.runtime.state();refused(h,IllegalStateException.class,()->f.event("test:heal",f.actor));refused(h,IllegalStateException.class,f.runtime::prepare);
            refused(h,IllegalStateException.class,()->f.host.grant(f.key(),.1,.1));refused(h,IllegalStateException.class,f.host::checkpoint);refused(h,IllegalStateException.class,f.host::finishRestore);
            h.assertTrue(f.runtime.state()==before&&f.runtime.failure().isEmpty(),"incomplete startup accepted or poisoned world work");
            var replacement=Objects.requireNonNull(EntityTypes.COW.create(f.local,EntitySpawnReason.LOAD));
            try{replacement.load(TagValueInput.create(ProblemReporter.DISCARDING,f.local.registryAccess(),output.buildResult()));
                near(h,replacement.getMaxHealth(),130,"NBT query cannot read unconnected remote session");near(h,replacement.getHealth(),130,"saved health clamped during staged startup");
                h.assertValueEqual(f.heals,1,"startup replayed healing");f.host.restoreLevel(f.remote);f.host.finishRestore();
                h.assertValueEqual(f.host.checkpoint(),saved,"connecting levels changed resource checkpoint");
            }finally{replacement.discard();}
            f.event("test:heal",f.actor);f.settled();near(h,f.actor.getHealth(),140,"completed startup cannot execute");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_save")
    public void laterNativeConnectionFailureRetainsEvidenceUntilWholeGroupCleanup(GameTestHelper h){
        try(var f=projection(h)){
            f.transfer();var saved=f.host.checkpoint();f.host.close();var dimensions=new TreeMap<>(saved.dimensions());var remote=dimensions.get(domain(f.remote));
            dimensions.put(domain(f.remote),new MinecraftEffectRuntime.Checkpoint(remote.dimension(),remote.gameTime()+1,remote.effects(),remote.equipmentOwners(),remote.actorOwners(),remote.abilityOwners()));
            f.host=MinecraftResourceRuntime.prepareRestore(f.local.getServer(),new MinecraftResourceRuntime.Checkpoint(saved.resources(),dimensions),policies(f));f.runtime=f.host.restoreLevel(f.local);
            refused(h,IllegalArgumentException.class,()->f.host.restoreLevel(f.remote));refused(h,IllegalStateException.class,f.host::finishRestore);
            h.assertTrue(MinecraftEffectRuntime.installed(f.local).isPresent(),"failure discarded attached evidence");refused(h,IllegalStateException.class,f.runtime::prepare);
            f.host.close();h.assertTrue(MinecraftEffectRuntime.installed(f.local).isEmpty()&&MinecraftEffectRuntime.installed(f.remote).isEmpty(),"staged cleanup leaked runtime");
        }h.succeed();
    }
    static final Identifier PROGRAM=Identifier.parse("chorus_gametest:checkpoint_restart");
    @GameCase(environment="chorus_gametest:resource_save")
    public void catalogueSaveCombinesResourceDirectoryNativeMetadataAndUnrelatedLocalDimensions(GameTestHelper h){
        var server=h.getLevel().getServer();var player=NativeMeleeGameTest.player(h);MinecraftEffectRuntime local=null;
        try{
            var runtime=EffectPrograms.installCoordinated(h.getLevel(),PROGRAM,EffectState.Mode.PVP);
            var remote=Objects.requireNonNull(server.getLevel(Level.NETHER));EffectPrograms.installCoordinated(remote,PROGRAM,EffectState.Mode.PVE);
            local=EffectPrograms.install(Objects.requireNonNull(server.getLevel(Level.END)),PROGRAM,EffectState.Mode.PVE);
            var host=EffectPrograms.resourceRuntime(server).orElseThrow();
            PlayerAbilities.get(player).configure(player,new AbilityLoadout(Map.of("test:grenade","test:restart_ability")),0);
            h.assertValueEqual(runtime.useAbility(player,"test:grenade").outcome(),AbilityUse.Outcome.ACCEPTED,"native payment");host.transfer(id(player),remote);
            try(var boundary=MinecraftCheckpointBoundary.open(server)){
                var archive=boundary.archive();h.assertValueEqual(archive.dimensions().size(),3,"lost unrelated local dimension");
                h.assertValueEqual(archive.resourceDirectory().orElseThrow().holders().get(id(player)).lease().domain(),domain(remote),"lost current resource owner");
                h.assertValueEqual(archive.dimensions().stream().filter(e->e.effects().resourceRouting()).count(),2L,"compiled modes changed");
                h.assertValueEqual(CheckpointArchive.decode(archive.encode()),archive,"joint native archive roundtrip");
                boundary.saveNative().requireSuccess();boundary.requireUnchanged();
            }
            h.assertTrue(runtime.failure().isEmpty(),"native save failed runtime");
        }finally{EffectPrograms.resourceRuntime(server).ifPresent(MinecraftResourceRuntime::close);if(local!=null)local.close();server.getPlayerList().remove(player);player.discard();}
        h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_save")
    public void frozenNativeSaveRejectsDirectoryOperationsEvenWhenCallerCatchesTheError(GameTestHelper h){
        var server=h.getLevel().getServer();var player=NativeMeleeGameTest.player(h);EffectPrograms.installCoordinated(h.getLevel(),PROGRAM,EffectState.Mode.PVE);
        var host=EffectPrograms.resourceRuntime(server).orElseThrow();
        try{
            host.claim("save-owner",h.getLevel());
            var mutations=List.<Runnable>of(()->host.claim("new-owner",h.getLevel()),()->host.transfer("save-owner",h.getLevel()),
                    ()->host.grant(new ResourceState.Key("save-owner","test:restart_energy"),.1,.1),()->host.drain(1),host::close,()->host.runtime(h.getLevel()).prepare());
            for(var mutation:mutations){
                try(var boundary=MinecraftCheckpointBoundary.open(server)){
                    // Preparing the save may legitimately enroll a joining player before the freeze.
                    var directory=boundary.archive().resourceDirectory().orElseThrow();
                    h.assertTrue(directory.holders().containsKey(id(player)),"save did not enroll indexed player before freezing");
                    refused(h,IllegalStateException.class,mutation);h.assertTrue(!boundary.issues().isEmpty(),"caught operation erased save violation");
                    refused(h,IllegalStateException.class,boundary::requireUnchanged);
                    h.assertValueEqual(host.snapshot().directory(),directory,"rejected operation changed frozen resource directory");
                }
            }
        }finally{host.close();server.getPlayerList().remove(player);player.discard();}
        h.assertTrue(EffectPrograms.resourceRuntime(server).isEmpty(),"closed catalogue host remained registered");h.succeed();
    }
}
