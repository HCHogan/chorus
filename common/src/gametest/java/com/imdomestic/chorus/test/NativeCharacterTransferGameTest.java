package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.*;
import static com.imdomestic.chorus.test.ResourceRuntimeGameTest.domain;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.actor.ActorBinding;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Actual installed runtimes follow ServerPlayer.teleport without test-side core handoff calls. */
public class NativeCharacterTransferGameTest {
    static final long START=10_000_000;
    static CompiledEffects program() { return compile(data()); }
    static com.google.gson.JsonObject data() {
        return object("""
            {"version":"native-character-transfer","resources":[{"id":"test:energy","capacity":1,"initial":1}],
             "buffs":[{"definition":{"id":"test:carry","version":"native-character-transfer","duration":1.5}}],
             "abilities":[{"id":"test:ability","slot":"test:grenade","cost":{"resource":"test:energy","amount":{"type":"chorus:constant","value":0.25,"unit":"charge_fraction"}},
               "effects":{"passive":{"bundle":"test:gear"}},"on_use":[{"type":"chorus:grant_buff","buff":"test:carry"}]}],
             "equipment":{"slots":[{"id":"test:primary","accepts":["test:weapon"],"weapon":true},{"id":"test:secondary","accepts":["test:weapon"],"weapon":true}],
               "items":[{"id":"test:gun","tags":["test:weapon"],"effects":{"passive":{"bundle":"test:gear"}}}]},
             "weapons":[{"item":"test:gun","ammunition":{"capacity":5,"magazine":3,"reserves":{"rounds":12,"capacity":20}},
               "reload":{"value":{"type":"chorus:constant","value":0.5,"unit":"second"}},
               "fire":{"cost":{"type":"chorus:constant","value":1,"unit":"round"},"interval":{"type":"chorus:constant","value":0.15,"unit":"second"},"on_fire":[]}}],
             "bundles":[{"id":"test:gear","rules":[
               {"id":"attach","on":"chorus:source_attached","if":{"type":"chorus:own_source"},"do":[{"type":"chorus:play_cue","cue":"test:attached"}]},
               {"id":"reload","on":"chorus:reload_finished","if":{"type":"chorus:source_is","source":"this_weapon"},"do":[{"type":"chorus:heal","amount":{"type":"chorus:constant","value":4,"unit":"damage"}}]}]},
              {"id":"test:actor","rules":[{"id":"attach","on":"chorus:source_attached","if":{"type":"chorus:own_source"},"do":[
                {"type":"chorus:initialize_resource","resource":"test:energy"},{"type":"chorus:play_cue","cue":"test:attached"}]}]}]}
            """);
    }
    static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final ServerLevel local,remote;final ServerPlayer player;final String holder;
        final Vec3 home;final CompiledEffects program;MinecraftResourceRuntime host;
        MinecraftEffectRuntime departure,arrival;final List<String> cues=new ArrayList<>();int heals,damageCalls;
        final AbilityLoadout selected=new AbilityLoadout(Map.of("test:grenade","test:ability"));
        final ActorBinding actor=new ActorBinding("test:actor");
        Fixture(GameTestHelper h){this(h,false,program());}
        Fixture(GameTestHelper h,boolean conflict){this(h,conflict,program());}
        Fixture(GameTestHelper h,CompiledEffects program){this(h,false,program);}
        Fixture(GameTestHelper h,boolean conflict,CompiledEffects program) {
            this.h=h;this.program=program;local=h.getLevel();remote=Objects.requireNonNull(local.getServer().getLevel(Level.NETHER));
            player=NativeMeleeGameTest.player(h);holder=player.getUUID().toString();home=h.absoluteVec(new Vec3(3,42,3));player.setPos(home);player.setHealth(1);
            host=new MinecraftResourceRuntime(local.getServer());
            try {
                departure=host.install(local,program,EffectState.empty().withBuffs(new BuffStore(START,1,Map.of())),clock(),r->world(local,r),MinecraftEffectRuntime::nativeSource);
                var target=conflict?EffectState.empty().withAmmo(new AmmoState(weapon("a"),1,5,Optional.empty())):EffectState.empty();
                arrival=host.install(remote,program,target,clock(),r->world(remote,r),MinecraftEffectRuntime::nativeSource);
                PlayerActor.get(player).configure(player,Optional.of(actor),0);
                PlayerAbilities.get(player).configure(player,selected,0);
                var equipment=PlayerEquipment.get(player);
                for(String suffix:List.of("a","b")) {
                    player.getInventory().setItem(0,stack(suffix));
                    equipment.swap(player,suffix.equals("a")?"test:primary":"test:secondary",0,equipment.revision());
                }
                equipment.draw(player,Optional.of("test:primary"),equipment.revision());
                h.assertValueEqual(departure.useAbility(player,"test:grenade").outcome(),AbilityUse.Outcome.ACCEPTED,"initial native ability");
                healthy();
            }catch(RuntimeException|Error failure){host.close();local.getServer().getPlayerList().remove(player);player.discard();throw failure;}
        }
        String weapon(String suffix){return holder+"/"+suffix;}
        ItemStack stack(String suffix){
            var item=new ItemStack(Items.DIAMOND_SWORD);item.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear(weapon(suffix),"test:gun",Map.of()));
            item.setDamageValue(7);item.set(DataComponents.CUSTOM_NAME,Component.literal("Transfer "+suffix));return item;
        }
        RuleEngine.ActionResult world(ServerLevel level,RuleEngine.WorldRequest request){
            if(request.command() instanceof com.imdomestic.chorus.effect.combat.DamageCommand)damageCalls++;
            var actions=new MinecraftWorldActions(level,id->level.getEntity(UUID.fromString(id)) instanceof LivingEntity e?e:null,
                    _->level.damageSources().generic(),(_,_) -> true,cue->cues.add(cue.cue()));
            var result=actions.apply(request);if(result instanceof HealingReceipt)heals++;return result;
        }
        EffectState state(ServerLevel level){return host.runtime(level).state().engine().domain();}
        ResourceState.Key key(){return new ResourceState.Key(holder,"test:energy");}
        void healthy(){
            for(var runtime:List.of(departure,arrival))h.assertTrue(runtime.failure().isEmpty()&&runtime.state().idle(),"native transfer runtime failed: "+runtime.failure());
        }
        ServerPlayer move(ServerLevel level,TeleportTransition.PostTeleportTransition callback){
            var position=level==local?home:new Vec3(12,90,12);level.getChunk(BlockPos.containing(position));
            return player.teleport(new TeleportTransition(level,position,Vec3.ZERO,0,0,callback));
        }
        void located(ServerLevel level){
            var other=level==local?remote:local;
            h.assertTrue(player.level()==level&&level.getEntity(player.getUUID())==player&&other.getEntity(player.getUUID())==null,"physical player and level index differ");
            h.assertValueEqual(host.snapshot().directory().owner(holder).lease().domain(),domain(level),"resource lease did not follow native transport");
            var state=state(level);h.assertTrue(state.equipment().containsKey(holder),"equipment projection did not follow native player");
            h.assertValueEqual(state.abilities().get(holder),selected,"ability selection lost during native transport");
            h.assertValueEqual(state.actors().get(holder),actor,"actor selection lost during native transport");
            h.assertTrue(state(other).sources().values().stream().noneMatch(s->s.holder().equals(holder)),"departure retained character source roots");
            near(h,state.resources().get(key()).value(),.75,"native transport reset or duplicated spent energy");
        }
        void containers(PlayerEquipment.Stored equipment,PlayerAbilities.Stored abilities,PlayerActor.Stored actor){
            var current=PlayerEquipment.get(player).snapshot();
            h.assertValueEqual(current.revision(),equipment.revision(),"transport consumed equipment revision");
            h.assertValueEqual(current.drawn(),equipment.drawn(),"transport changed drawn slot");
            h.assertValueEqual(current.items().keySet(),equipment.items().keySet(),"transport lost owned slots");
            for(var entry:equipment.items().entrySet())h.assertTrue(ItemStack.matches(current.items().get(entry.getKey()),entry.getValue()),"transport changed item components");
            h.assertValueEqual(PlayerAbilities.get(player).snapshot(),abilities,"transport rewrote native ability save");
            h.assertValueEqual(PlayerActor.get(player).snapshot(),actor,"transport rewrote native actor save");
        }
        /** Same-process restore of an encoded joint archive; the actual native worlds and player stay loaded. */
        MinecraftResourceRuntime.Checkpoint restoreArchive(){
            var saved=host.checkpoint();
            var entries=saved.dimensions().values().stream().map(value->new CheckpointArchive.Entry("test:native_character_transfer",value.dimension(),value.gameTime(),value.effects(),value.equipmentOwners(),value.actorOwners(),value.abilityOwners())).toList();
            var archive=CheckpointArchive.coordinated(entries,saved.resources());
            var decoded=CheckpointArchive.decode(com.google.gson.JsonParser.parseString(archive.encode().toString()));
            h.assertValueEqual(decoded,archive,"joint character archive changed during encoding");
            var dimensions=new TreeMap<String,MinecraftEffectRuntime.Checkpoint>();
            decoded.dimensions().forEach(value->dimensions.put(value.dimension(),new MinecraftEffectRuntime.Checkpoint(value.dimension(),value.gameTime(),value.effects(),value.equipmentOwners(),value.actorOwners(),value.abilityOwners())));
            var restored=new MinecraftResourceRuntime.Checkpoint(decoded.resourceCheckpoint().orElseThrow(),dimensions);
            var policies=new TreeMap<String,MinecraftResourceRuntime.RestoreBinding>();
            for(var level:List.of(local,remote))policies.put(domain(level),new MinecraftResourceRuntime.RestoreBinding(level,program,clock(),r->world(level,r),MinecraftEffectRuntime::nativeSource));
            host.close();
            h.assertTrue(MinecraftEffectRuntime.installed(local).isEmpty()&&MinecraftEffectRuntime.installed(remote).isEmpty(),"old host retained native runtime while restoring");
            host=MinecraftResourceRuntime.restore(local.getServer(),restored,policies);departure=host.runtime(local);arrival=host.runtime(remote);
            h.assertValueEqual(host.checkpoint(),saved,"same-process restore changed joint character checkpoint");return saved;
        }
        @Override public void close(){try{host.close();}finally{local.getServer().getPlayerList().remove(player);player.discard();}}
    }

    @GameCase(environment="chorus_gametest:native_character_roundtrip",maxTicks=30)
    public void automaticRoundTripKeepsContainersBuffsPaidEnergyAndRemainingReload(GameTestHelper h){
        var f=new Fixture(h);
        try {
            var equipment=PlayerEquipment.get(f.player).snapshot();var abilities=PlayerAbilities.get(f.player).snapshot();var actor=PlayerActor.get(f.player).snapshot();
            int attached=f.cues.size();
            h.assertValueEqual(f.departure.reload(f.player).outcome(),WeaponReload.Outcome.ACCEPTED,"native reload rejected");
            var accepted=f.state(f.local).reloads().get(f.holder);
            h.runAfterDelay(2,()->{try{
                f.departure.prepare();f.arrival.prepare();f.healthy();
                long remaining=accepted.dueTime()-f.state(f.local).buffs().timeMicros();
                h.assertTrue(remaining>0&&remaining<500_000,"fixture did not advance accepted reload");
                long buffRemaining=f.state(f.local).buffs().instances().values().stream().filter(b->b.key().holder().equals(f.holder)).findFirst().orElseThrow().deadline()-f.state(f.local).buffs().timeMicros();
                h.assertTrue(f.move(f.remote,entity->f.located(f.remote))==f.player,"native transfer returned another player");
                f.located(f.remote);f.containers(equipment,abilities,actor);
                var moved=f.state(f.remote).reloads().get(f.holder);
                h.assertValueEqual(moved.completionId(),accepted.completionId(),"native handoff changed completion identity");
                h.assertValueEqual(moved.dueTime()-f.state(f.remote).buffs().timeMicros(),remaining,"native handoff restarted reload");
                var buff=f.state(f.remote).buffs().instances().values().stream().filter(b->b.key().holder().equals(f.holder)).findFirst().orElseThrow();
                h.assertValueEqual(buff.deadline()-f.state(f.remote).buffs().timeMicros(),buffRemaining,"native handoff reset buff duration");
                h.assertTrue(f.state(f.local).ammunition().keySet().stream().noneMatch(w->w.startsWith(f.holder)),"departure retained carried ammunition");
                f.move(f.local,entity->f.located(f.local));f.located(f.local);f.containers(equipment,abilities,actor);
                h.assertValueEqual(f.state(f.local).reloads().get(f.holder).completionId(),accepted.completionId(),"return replaced accepted reload");
                h.assertValueEqual(f.cues.size(),attached,"round trip replayed source attachment");
                f.host.checkpoint();
            }catch(RuntimeException|Error error){f.close();throw error;}});
            h.runAfterDelay(13,()->{try(f){
                f.departure.prepare();f.arrival.prepare();f.healthy();f.located(f.local);
                var ammo=f.state(f.local).ammunition().get(f.weapon("a"));
                h.assertValueEqual(ammo.magazine(),5,"remaining reload never completed");h.assertValueEqual(ammo.reserve().orElseThrow().rounds(),10,"reload paid reserve twice");
                h.assertTrue(!f.state(f.local).reloads().containsKey(f.holder),"completed reload survived");
                h.assertValueEqual(f.heals,1,"reload world reaction skipped or repeated");near(h,f.player.getHealth(),5,"reload did not heal actual returned player");
                h.assertValueEqual(f.cues.size(),attached,"native refresh replayed attachments");f.containers(equipment,abilities,actor);f.host.checkpoint();h.succeed();
            }});
        }catch(RuntimeException|Error error){f.close();throw error;}
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void unequippedNativeInventoryWeaponCarriesItsExistingAmmoAndShotCadence(GameTestHelper h){
        try(var f=new Fixture(h)){
            var equipment=PlayerEquipment.get(f.player);f.player.getInventory().setItem(0,f.stack("bag"));
            equipment.swap(f.player,"test:primary",0,equipment.revision());equipment.draw(f.player,Optional.of("test:primary"),equipment.revision());
            h.assertValueEqual(f.departure.fire(f.player).outcome(),WeaponFire.Outcome.ACCEPTED,"bag weapon firing fixture");
            var ammo=f.state(f.local).ammunition().get(f.weapon("bag"));var shot=f.state(f.local).shots().get(f.weapon("bag"));
            equipment.swap(f.player,"test:primary",0,equipment.revision());equipment.draw(f.player,Optional.of("test:primary"),equipment.revision());
            var stack=f.player.getInventory().getItem(0).copy();int attached=f.cues.size();
            h.assertValueEqual(ammo.magazine(),2,"fixture did not consume a real round");
            f.move(f.remote,TeleportTransition.DO_NOTHING);f.located(f.remote);
            h.assertTrue(ItemStack.matches(f.player.getInventory().getItem(0),stack),"native inventory stack changed");
            h.assertValueEqual(f.state(f.remote).ammunition().get(f.weapon("bag")),ammo,"unequipped carried ammo reinitialized");
            var moved=f.state(f.remote).shots().get(f.weapon("bag"));
            h.assertValueEqual(moved.cost(),shot.cost(),"native transfer paid shot again");
            h.assertValueEqual(moved.readyTime()-f.state(f.remote).buffs().timeMicros(),shot.readyTime()-f.state(f.local).buffs().timeMicros(),"inventory cadence changed");
            h.assertTrue(!f.state(f.local).ammunition().containsKey(f.weapon("bag"))&&!f.state(f.local).shots().containsKey(f.weapon("bag")),"unequipped carried weapon was left behind");
            h.assertValueEqual(f.cues.size(),attached,"inventory handoff reattached equipped sources");f.healthy();f.host.checkpoint();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_character_restore",maxTicks=30)
    public void transferredCharacterArchiveRestoresBothNativeDomainsAndCompletesRemainingReloadOnce(GameTestHelper h){
        var f=new Fixture(h);
        try{
            var equipment=PlayerEquipment.get(f.player).snapshot();var abilities=PlayerAbilities.get(f.player).snapshot();var actor=PlayerActor.get(f.player).snapshot();int attached=f.cues.size();
            h.assertValueEqual(f.departure.reload(f.player).outcome(),WeaponReload.Outcome.ACCEPTED,"restore reload fixture rejected");
            var accepted=f.state(f.local).reloads().get(f.holder);long[] buffRemaining={0},savedTime={0};
            h.runAfterDelay(2,()->{try{
                f.move(f.remote,TeleportTransition.DO_NOTHING);f.located(f.remote);
                var before=f.state(f.remote);long reloadRemaining=before.reloads().get(f.holder).dueTime()-before.buffs().timeMicros();
                h.assertTrue(reloadRemaining>0&&reloadRemaining<500_000,"restore fixture did not advance reload");
                savedTime[0]=before.buffs().timeMicros();buffRemaining[0]=before.buffs().instances().values().stream().filter(b->b.key().holder().equals(f.holder)).findFirst().orElseThrow().deadline()-savedTime[0];
                var saved=f.restoreArchive();var source=saved.dimensions().get(domain(f.local));var target=saved.dimensions().get(domain(f.remote));var id=f.player.getUUID();
                h.assertTrue(!source.equipmentOwners().contains(id)&&!source.actorOwners().contains(id)&&!source.abilityOwners().contains(id),"departure archive retained native validation ownership");
                h.assertTrue(target.equipmentOwners().contains(id)&&target.actorOwners().contains(id)&&target.abilityOwners().contains(id),"arrival archive lost native validation ownership");
                f.located(f.remote);f.containers(equipment,abilities,actor);f.healthy();
                var restored=f.state(f.remote);var reload=restored.reloads().get(f.holder);
                h.assertValueEqual(reload.completionId(),accepted.completionId(),"restored reload changed completion identity");
                h.assertValueEqual(reload.dueTime()-restored.buffs().timeMicros(),reloadRemaining,"restore restarted or shortened reload");
                h.assertValueEqual(restored.buffs().instances().values().stream().filter(b->b.key().holder().equals(f.holder)).findFirst().orElseThrow().deadline()-restored.buffs().timeMicros(),buffRemaining[0],"restore restarted or shortened buff");
                h.assertValueEqual(f.cues.size(),attached,"archive restore replayed source attachment");h.assertValueEqual(f.heals,0,"archive restore executed pending reload early");near(h,f.player.getHealth(),1,"archive restore changed physical health");
            }catch(RuntimeException|Error error){f.close();throw error;}});
            h.runAfterDelay(13,()->{try(f){
                f.departure.prepare();f.arrival.prepare();f.healthy();f.located(f.remote);f.containers(equipment,abilities,actor);
                var state=f.state(f.remote);var ammo=state.ammunition().get(f.weapon("a"));
                h.assertTrue(!state.reloads().containsKey(f.holder),"restored reload never completed");
                h.assertValueEqual(ammo.magazine(),5,"restored reload did not fill magazine");h.assertValueEqual(ammo.reserve().orElseThrow().rounds(),10,"restored reload paid reserves twice");
                h.assertValueEqual(f.heals,1,"restored reload skipped or repeated actual healing");near(h,f.player.getHealth(),5,"restored reload did not heal current native owner");
                var buff=state.buffs().instances().values().stream().filter(b->b.key().holder().equals(f.holder)).findFirst().orElseThrow();
                h.assertValueEqual(buff.deadline()-state.buffs().timeMicros(),buffRemaining[0]-(state.buffs().timeMicros()-savedTime[0]),"restored buff countdown drifted");
                f.restoreArchive();h.assertValueEqual(f.heals,1,"restoring completed reload replayed actual healing");
                h.assertValueEqual(f.cues.size(),attached,"restoring completed character replayed attachment");f.located(f.remote);f.containers(equipment,abilities,actor);f.healthy();h.succeed();
            }});
        }catch(RuntimeException|Error error){f.close();throw error;}
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void destinationOwnershipConflictRejectsBeforeTheNativePlayerLeavesItsWorld(GameTestHelper h){
        try(var f=new Fixture(h,true)){
            var before=f.host.checkpoint();var position=f.player.position();int attached=f.cues.size();int[] callbacks={0};
            refused(h,IllegalStateException.class,()->f.move(f.remote,entity->callbacks[0]++));
            h.assertTrue(f.player.level()==f.local&&f.local.getEntity(f.player.getUUID())==f.player&&!f.player.isRemoved(),"preflight conflict partially moved player");
            h.assertValueEqual(f.player.position(),position,"preflight conflict changed native position");
            h.assertValueEqual(callbacks[0],0,"preflight rejection ran post-teleport callback");
            h.assertValueEqual(f.host.checkpoint(),before,"preflight conflict partially published character");
            h.assertValueEqual(f.cues.size(),attached,"preflight rejection replayed source attachment");f.healthy();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void postTeleportFailureRetainsActualArrivalAndPublishedStateWithoutRetryOrCheckpoint(GameTestHelper h){
        try(var f=new Fixture(h)){
            int attached=f.cues.size();int[] callbacks={0};
            refused(h,IllegalStateException.class,()->f.move(f.remote,entity->{
                callbacks[0]++;f.located(f.remote);f.player.setHealth(7);throw new IllegalStateException("Lost post-teleport callback outcome");
            }));
            h.assertTrue(f.player.level()==f.remote&&f.remote.getEntity(f.player.getUUID())==f.player&&f.local.getEntity(f.player.getUUID())==null,"unknown callback rolled back physical arrival");
            h.assertTrue(f.arrival.failure().isPresent(),"unknown native callback left arrival healthy");
            h.assertTrue(f.arrival.state().engine().domain().equipment().containsKey(f.holder),"unknown callback erased published equipment");
            near(h,f.arrival.state().engine().domain().resources().get(f.key()).value(),.75,"unknown callback erased migrated energy");
            near(h,f.player.getHealth(),7,"unknown callback rolled back actual world write");
            refused(h,IllegalStateException.class,f.host::checkpoint);
            MinecraftEffectRuntime.tick(f.local);MinecraftEffectRuntime.tick(f.remote);
            refused(h,IllegalStateException.class,()->f.move(f.local,entity->callbacks[0]++));
            h.assertValueEqual(callbacks[0],1,"unknown callback replayed");h.assertValueEqual(f.cues.size(),attached,"unknown transport replayed attachments");
            h.assertTrue(f.player.level()==f.remote,"failed runtime allowed a second transport");
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void failureAfterNativeRemovalRetainsSourceCoreAndActualRemovalWithoutCheckpoint(GameTestHelper h){
        try(var f=new Fixture(h)){
            f.host.checkpoint();var source=f.state(f.local);var target=f.state(f.remote);int attached=f.cues.size();int[] removals={0},callbacks={0};
            try(var probe=NativeTransferTestProbe.afterRemoval(f.player,()->{
                removals[0]++;throw new IllegalStateException("Native transport failed after source removal");
            })){
                refused(h,IllegalStateException.class,()->f.move(f.remote,entity->callbacks[0]++));
            }
            h.assertValueEqual(removals[0],1,"source removal probe never ran");
            h.assertTrue(f.player.isRemoved()&&f.player.level()==f.local&&f.local.getEntity(f.player.getUUID())==null&&f.remote.getEntity(f.player.getUUID())==null,"failed removal was rolled back or inserted into destination");
            h.assertValueEqual(f.departure.state().engine().domain(),source,"pre-insertion failure moved or changed source core");
            h.assertValueEqual(f.arrival.state().engine().domain(),target,"pre-insertion failure published target core");
            h.assertTrue(f.departure.failure().isPresent()&&f.arrival.failure().isPresent(),"uncertain removal did not stop whole resource host");
            h.assertValueEqual(callbacks[0],0,"pre-insertion failure ran post-teleport callback");
            refused(h,IllegalStateException.class,f.host::checkpoint);
            MinecraftEffectRuntime.tick(f.local);MinecraftEffectRuntime.tick(f.remote);
            h.assertValueEqual(f.departure.state().engine().domain(),source,"failed host replayed removal work");
            h.assertValueEqual(f.cues.size(),attached,"pre-insertion failure replayed source attachment");
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void caughtFencedWriteStillRejectsPublicationAfterActualDestinationInsertion(GameTestHelper h){
        try(var f=new Fixture(h)){
            f.host.checkpoint();var source=f.state(f.local);var target=f.state(f.remote);int attached=f.cues.size();int[] denied={0},callbacks={0};
            try(var probe=NativeTransferTestProbe.afterRemoval(f.player,()->{
                try{f.host.grant(f.key(),.1,.1);}catch(IllegalStateException expected){denied[0]++;}
            })){
                refused(h,IllegalStateException.class,()->f.move(f.remote,entity->callbacks[0]++));
            }
            h.assertValueEqual(denied[0],1,"host write was not rejected while native transport was fenced");
            h.assertTrue(!f.player.isRemoved()&&f.player.level()==f.remote&&f.remote.getEntity(f.player.getUUID())==f.player&&f.local.getEntity(f.player.getUUID())==null,"sticky violation rolled back real destination insertion");
            h.assertValueEqual(f.departure.state().engine().domain(),source,"caught fence violation allowed source publication or resource write");
            h.assertValueEqual(f.arrival.state().engine().domain(),target,"caught fence violation allowed target character publication");
            h.assertTrue(f.departure.failure().isPresent()&&f.arrival.failure().isPresent(),"caught fence violation did not stop whole host");
            h.assertValueEqual(callbacks[0],0,"unpublished character reached post-teleport callback");
            refused(h,IllegalStateException.class,f.host::checkpoint);
            refused(h,IllegalStateException.class,()->f.move(f.local,entity->callbacks[0]++));
            MinecraftEffectRuntime.tick(f.local);MinecraftEffectRuntime.tick(f.remote);
            h.assertValueEqual(f.departure.state().engine().domain(),source,"failed host replayed staged core transfer");
            h.assertValueEqual(f.cues.size(),attached,"fenced write replayed source attachment");
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void postTeleportMayStartAnotherTransferAfterTheFirstCharacterPublication(GameTestHelper h){
        try(var f=new Fixture(h)){
            int attached=f.cues.size();int[] callbacks={0};
            var original=f.host.snapshot().directory().owner(f.holder).lease();
            // A callback returning to A makes vanilla's outer spectator tail select the player itself recursively.
            // Three distinct worlds exercise a real nested handoff without that unrelated native behavior.
            var onwardLevel=Objects.requireNonNull(f.local.getServer().getLevel(Level.END));
            var onward=f.host.install(onwardLevel,f.program,EffectState.empty(),clock(),r->f.world(onwardLevel,r),MinecraftEffectRuntime::nativeSource);
            f.move(f.remote,entity->{
                callbacks[0]++;f.located(f.remote);
                f.move(onwardLevel,returned->{callbacks[0]++;f.located(onwardLevel);});
            });
            f.located(onwardLevel);f.departure.prepare();f.arrival.prepare();onward.prepare();f.healthy();
            h.assertTrue(onward.failure().isEmpty()&&onward.state().idle(),"nested arrival runtime failed");
            h.assertTrue(f.state(f.remote).sources().values().stream().noneMatch(s->s.holder().equals(f.holder)),"intermediate world retained character sources");
            h.assertValueEqual(callbacks[0],2,"nested native transport lost a callback");
            h.assertTrue(f.host.snapshot().directory().owner(f.holder).lease().generation()>original.generation(),"nested transport did not publish fresh ownership");
            h.assertValueEqual(f.cues.size(),attached,"nested post-teleport replayed source attachment");
            h.assertValueEqual(f.state(onwardLevel).ammunition().get(f.weapon("a")).magazine(),3,"nested transfer reinitialized or spent ammunition");f.host.checkpoint();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void ordinarySameDimensionTeleportKeepsTheExistingResourceLeaseAndCharacter(GameTestHelper h){
        try(var f=new Fixture(h)){
            var lease=f.host.snapshot().directory().owner(f.holder).lease();var state=f.state(f.local);int attached=f.cues.size();int[] callbacks={0};
            var destination=f.home.add(3,0,0);
            h.assertTrue(f.player.teleport(new TeleportTransition(f.local,destination,Vec3.ZERO,0,0,entity->callbacks[0]++))==f.player,"same-world native transport rejected");
            h.assertValueEqual(f.player.position(),destination,"same-world position did not move");
            h.assertValueEqual(f.host.snapshot().directory().owner(f.holder).lease(),lease,"same-world teleport replaced resource lease");
            h.assertValueEqual(f.state(f.local),state,"same-world teleport rewrote character state");
            h.assertValueEqual(callbacks[0],1,"same-world callback missing");h.assertValueEqual(f.cues.size(),attached,"same-world teleport replayed attachment");f.healthy();f.host.checkpoint();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:resource_runtime")
    public void nativeRemovedPlayerRejectionDoesNotStartOrFailACharacterTransfer(GameTestHelper h){
        try(var f=new Fixture(h)){
            f.player.discard();var lease=f.host.snapshot().directory().owner(f.holder).lease();int[] callbacks={0};
            h.assertTrue(f.move(f.remote,entity->callbacks[0]++)==null,"removed native player transport did not reject");
            h.assertTrue(f.player.level()==f.local&&f.remote.getEntity(f.player.getUUID())==null,"native rejection changed world");
            h.assertValueEqual(f.host.snapshot().directory().owner(f.holder).lease(),lease,"native rejection changed lease");
            h.assertValueEqual(callbacks[0],0,"native rejection ran callback");f.departure.prepare();f.arrival.prepare();f.healthy();f.host.checkpoint();
        }h.succeed();
    }
}
