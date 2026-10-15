package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.actor.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.weapon.WeaponReload;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

/** Explicit core handoff against real native containers and transport; automatic runtime enrollment is separate. */
public class CharacterTransferGameTest {
    static final String A="test:departure",B="test:arrival";
    static final long START=10_000_000;
    static CompiledEffects program() {
        return compile(object("""
            {"version":"character-handoff-world","resources":[{"id":"test:energy","capacity":1,"initial":1}],
             "buffs":[{"definition":{"id":"test:carry","version":"character-handoff-world","duration":1.5}}],
             "abilities":[{"id":"test:ability","slot":"test:grenade","cost":{"resource":"test:energy","amount":{"type":"chorus:constant","value":0.25,"unit":"charge_fraction"}},"effects":{"passive":{"bundle":"test:gear"}},"on_use":[]}],
             "equipment":{"slots":[{"id":"test:primary","accepts":["test:weapon"],"weapon":true},{"id":"test:secondary","accepts":["test:weapon"],"weapon":true}],
               "items":[{"id":"test:gun","tags":["test:weapon"],"effects":{"passive":{"bundle":"test:gear"}}}]},
             "weapons":[{"item":"test:gun","ammunition":{"capacity":5,"magazine":3,"reserves":{"rounds":12,"capacity":20}},
               "reload":{"value":{"type":"chorus:constant","value":0.2,"unit":"second"}}}],
             "bundles":[{"id":"test:gear","rules":[
               {"id":"attach","on":"chorus:source_attached","if":{"type":"chorus:own_source"},"do":[{"type":"chorus:play_cue","cue":"test:attached"}]},
               {"id":"reload","on":"chorus:reload_finished","if":{"type":"chorus:source_is","source":"this_weapon"},"do":[{"type":"chorus:heal","amount":{"type":"chorus:constant","value":4,"unit":"damage"}}]}]},
              {"id":"test:actor","rules":[
                {"id":"attach","on":"chorus:source_attached","if":{"type":"chorus:own_source"},"do":[{"type":"chorus:play_cue","cue":"test:attached"}]},
                {"id":"arm","on":"test:arm","do":[
                  {"type":"chorus:grant_buff","buff":"test:carry"},
                  {"action":{"type":"chorus:spend_resource","resource":"test:energy","payment":"cast","amount":{"type":"chorus:constant","value":0.6,"unit":"charge_fraction"}},"as":"paid"},
                  {"action":{"type":"chorus:retain_cost","cost":"paid","duration":{"type":"chorus:constant","value":5,"unit":"second"}},"as":"voucher"},
                  {"after":{"type":"chorus:constant","value":0.15,"unit":"second"},"do":[
                    {"type":"chorus:refund_retained_cost","cost":"voucher","fraction":{"type":"chorus:constant","value":0.5,"unit":"multiplier"}},
                    {"type":"chorus:heal","amount":{"type":"chorus:constant","value":2,"unit":"damage"}},
                    {"type":"chorus:close_retained_cost","cost":"voucher"},
                    {"type":"chorus:refund_retained_cost","cost":"voucher","fraction":{"type":"chorus:constant","value":1,"unit":"multiplier"}}]},
                  {"type":"chorus:schedule","name":"pulse","event":"test:pulse","delay":{"type":"chorus:constant","value":0.2,"unit":"second"},"interval":{"type":"chorus:constant","value":0.2,"unit":"second"},"repeat":2},
                  {"after":{"type":"chorus:constant","value":0.25,"unit":"second"},"lifetime":"detached","do":[{"type":"chorus:play_cue","cue":"test:detached"}]}
                ]},
                {"id":"pulse","on":"test:pulse","if":{"type":"chorus:own_timer","name":"pulse"},"do":[{"type":"chorus:heal","amount":{"type":"chorus:constant","value":1,"unit":"damage"}}]}
              ]}]}
            """));
    }
    record Cue(String domain,String cue) {}
    static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final ServerPlayer player;final String holder;final ServerLevel local,remote;
        final CompiledEffects p=program();final Map<String,EffectSession> sessions=new TreeMap<>();final List<Cue> cues=new ArrayList<>();
        final Loadout loadout;final AbilityLoadout abilities=new AbilityLoadout(Map.of("test:grenade","test:ability"));
        final ActorBinding actor=new ActorBinding("test:actor");final Map<String,ItemStack> original=new TreeMap<>();
        ResourceSessionAuthority host;boolean loseHealing;int heals,verifications;
        Fixture(GameTestHelper h,boolean arm) {
            this.h=h;local=h.getLevel();remote=Objects.requireNonNull(local.getServer().getLevel(Level.NETHER));
            player=NativeMeleeGameTest.player(h);holder=player.getUUID().toString();player.setHealth(1);
            var gears=new TreeMap<String,Loadout.Gear>();
            for(String suffix:List.of("a","b")) {
                String slot=suffix.equals("a")?"test:primary":"test:secondary";
                var gear=new Loadout.Gear(holder+"/"+suffix,"test:gun",Map.of());gears.put(slot,gear);
                var stack=new ItemStack(Items.DIAMOND_SWORD);stack.set(ChorusComponents.EQUIPMENT.get(),gear);
                stack.setDamageValue(7);stack.set(DataComponents.CUSTOM_NAME,Component.literal("Carried "+suffix));original.put(slot,stack);
            }
            loadout=new Loadout(gears,Optional.of("test:primary"));
            // Restore legitimate native containers before constructing the explicit core projections.
            var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,local.registryAccess());
            output.store("chorus:equipment",PlayerEquipment.Stored.CODEC,new PlayerEquipment.Stored(original,loadout.drawn(),7));
            output.store(PlayerAbilities.SAVE_KEY,PlayerAbilities.Stored.CODEC,new PlayerAbilities.Stored(abilities,3));
            output.store(PlayerActor.SAVE_KEY,PlayerActor.Stored.CODEC,new PlayerActor.Stored(Optional.of(actor),4));
            var input=TagValueInput.create(ProblemReporter.DISCARDING,local.registryAccess(),output.buildResult());
            PlayerEquipment.get(player).load(input);PlayerAbilities.get(player).load(input);PlayerActor.get(player).load(input);
            sessions.put(A,new EffectSession(p,clock(),1,EffectState.empty().withBuffs(new BuffStore(START,1,Map.of())),r->execute(A,r)));
            sessions.put(B,new EffectSession(p,clock(),1,EffectState.empty(),r->execute(B,r)));
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,Map.of(
                    A,new ResourceSessionAuthority.Binding(p,sessions.get(A)),B,new ResourceSessionAuthority.Binding(p,sessions.get(B))));
            host.claim(holder,A);
            input(A,new EquipmentChange(holder,Loadout.EMPTY,loadout).signal());
            input(A,new AbilityChange(holder,AbilityLoadout.EMPTY,abilities).signal());
            input(A,new ActorChange(holder,Optional.empty(),Optional.of(actor)).signal());
            if(arm)input(A,new RuleEngine.Signal("test:arm",new EffectEvent(holder,holder,new BuffInstance.Origin(holder,"actor","",""),Set.of(),Map.of())));
        }
        ResourceState.Key key(){return new ResourceState.Key(holder,"test:energy");}
        String weapon(){return loadout.slots().get("test:primary").instance();}
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        void input(String domain,RuleEngine.Signal signal){sessions.get(domain).start(state(domain).buffs().timeMicros(),signal);}
        void at(String domain,long time){sessions.get(domain).observe(time,List.of());}
        RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request) {
            if(request.command() instanceof ResourceRequests.Request)return host.execute(domain,request);
            var level=domain.equals(A)?local:remote;
            if(request.command() instanceof WeaponReload.Verify query) {
                // This fixture is an explicit host. Its verification reads the actual container, not the core projection.
                var equipment=PlayerEquipment.get(player).snapshot();
                boolean allowed=level.getEntity(player.getUUID())==player&&player.isAlive()&&!player.isSpectator()
                        &&equipment.drawn().map(equipment.items()::get).map(stack->stack.get(ChorusComponents.EQUIPMENT.get())).filter(query.plan().gear()::equals).isPresent();
                verifications++;return new WeaponReload.Verified(query,allowed);
            }
            var world=new MinecraftWorldActions(level,id->level.getEntity(UUID.fromString(id)) instanceof LivingEntity entity?entity:null,
                    _->level.damageSources().generic(),(_,_) -> true,cue->cues.add(new Cue(domain,cue.cue())));
            var result=world.apply(request);
            if(result instanceof HealingReceipt){heals++;if(loseHealing)throw new IllegalStateException("Lost receipt after relocated source healing");}
            return result;
        }
        void transfer() {
            var move=host.prepareCharacterTransfer(holder,A,B,loadout.slots().values().stream().map(Loadout.Gear::instance).collect(java.util.stream.Collectors.toSet()));
            remote.getChunk(new BlockPos(12,90,12));
            player.teleport(new TeleportTransition(remote,new Vec3(12,90,12),Vec3.ZERO,0,0,TeleportTransition.DO_NOTHING));
            h.assertTrue(remote.getEntity(player.getUUID())==player&&local.getEntity(player.getUUID())==null,"native player did not transfer");
            host.commit(move);containers();
        }
        void containers() {
            var equipment=PlayerEquipment.get(player).snapshot();h.assertValueEqual(equipment.revision(),7L,"migration mutated native container revision");
            h.assertValueEqual(equipment.drawn(),loadout.drawn(),"native drawn selection changed");
            for(var entry:original.entrySet())h.assertTrue(ItemStack.matches(equipment.items().get(entry.getKey()),entry.getValue()),"physical stack components changed");
            h.assertValueEqual(PlayerAbilities.get(player).snapshot(),new PlayerAbilities.Stored(abilities,3),"native ability selection changed");
            h.assertValueEqual(PlayerActor.get(player).snapshot(),new PlayerActor.Stored(Optional.of(actor),4),"native actor selection changed");
        }
        void restore() {
            var saved=host.checkpoint();var domains=new TreeMap<String,EffectSession.Checkpoint>();
            saved.domains().forEach((domain,value)->domains.put(domain,EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(value))));
            var result=ResourceSessionAuthority.restore(new ResourceSessionAuthority.Checkpoint(ResourceAuthorityCodec.decode(ResourceAuthorityCodec.encode(saved.directory())),domains,
                    ParticipantPlacementCodec.decode(ParticipantPlacementCodec.encode(saved.placement()))),Map.of(
                    A,new ResourceSessionAuthority.RestoreBinding(p,clock(),1,r->execute(A,r)),B,new ResourceSessionAuthority.RestoreBinding(p,clock(),1,r->execute(B,r))));
            host=result.authority();sessions.clear();result.bindings().forEach((domain,value)->sessions.put(domain,value.session()));
            h.assertValueEqual(host.participants(),saved.placement(),"restore lost explicit participant placement");
            h.assertValueEqual(host.checkpoint(),saved,"restore changed committed character handoff");containers();
        }
        @Override public void close(){try{local.getServer().getPlayerList().remove(player);}finally{player.discard();}}
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void realPlayerContainersAndSourceOwnedHealingSurviveJointCharacterHandoffAndRestore(GameTestHelper h) {
        try(var f=new Fixture(h,true)) {
            var before=f.state(A);int attachments=f.cues.size();f.at(A,START+50_000);f.transfer();f.restore();
            h.assertValueEqual(f.cues.size(),attachments,"handoff replayed source attachment");
            h.assertValueEqual(f.state(B).sources(),before.sources(),"source roots changed");
            h.assertValueEqual(f.state(B).equipment().get(f.holder),f.loadout,"equipment projection missing");
            h.assertValueEqual(f.state(B).abilities().get(f.holder),f.abilities,"selected abilities missing");
            h.assertValueEqual(f.state(B).actors().get(f.holder),f.actor,"actor projection missing");
            h.assertTrue(f.state(A).sources().isEmpty()&&f.state(A).equipment().isEmpty()&&f.state(A).abilities().isEmpty()&&f.state(A).actors().isEmpty(),"departure retained character roots");
            h.assertValueEqual(f.state(B).buffs().instances().values().iterator().next().deadline(),1_450_000L,"buff duration reset");
            f.at(A,START+500_000);near(h,f.player.getHealth(),1,"departure ran moved source work");
            h.assertTrue(f.cues.contains(new Cue(A,"test:detached")),"detached historical work moved with player");
            f.at(B,99_999);near(h,f.player.getHealth(),1,"source delay shortened");
            f.at(B,100_000);f.host.drain(20);near(h,f.player.getHealth(),3,"arrival source healing missed actual player");
            near(h,f.state(B).resources().get(f.key()).value(),.7,"refund lost issuing ledger");h.assertTrue(f.state(A).retainedCosts().isEmpty(),"issuer voucher not closed");
            f.restore();f.at(B,350_000);near(h,f.player.getHealth(),5,"owned periodic work did not continue twice");
            h.assertValueEqual(f.heals,3,"handoff or restore duplicated healing");f.containers();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void transferredReloadUsesRemainingTimeAndActualContainerWithoutReinitializingAmmunition(GameTestHelper h) {
        try(var f=new Fixture(h,false)) {
            f.input(A,new WeaponReload.Request(f.holder,"carried-reload").signal());var original=f.state(A).reloads().get(f.holder);
            f.at(A,START+50_000);f.transfer();f.restore();
            var moved=f.state(B).reloads().get(f.holder);h.assertValueEqual(moved.completionId(),original.completionId(),"reload completion identity changed");
            h.assertValueEqual(moved.dueTime(),150_000L,"reload restarted its full duration");
            h.assertTrue(f.state(A).ammunition().isEmpty()&&f.state(A).reloads().isEmpty(),"departure retained carried ammo or reload");
            f.at(A,START+500_000);f.at(B,149_999);h.assertValueEqual(f.state(B).ammunition().get(f.weapon()).magazine(),3,"reload completed early");near(h,f.player.getHealth(),1,"early reload reaction");
            f.at(B,150_000);h.assertValueEqual(f.state(B).ammunition().get(f.weapon()).magazine(),5,"relocated reload did not fill physical weapon account");
            h.assertValueEqual(f.state(B).ammunition().get(f.weapon()).reserve().orElseThrow().rounds(),10,"reload duplicated finite reserves");
            h.assertValueEqual(f.verifications,1,"reload skipped or repeated actual container verification");near(h,f.player.getHealth(),5,"reload completion did not heal actual transferred owner");
            f.restore();f.at(B,400_000);h.assertValueEqual(f.heals,1,"restored completion replayed world healing");f.containers();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void unknownSourceHealingRetainsTransferredCharacterNativeHealthAndOriginalRefundClaim(GameTestHelper h) {
        try(var f=new Fixture(h,true)) {
            f.at(A,START+50_000);f.transfer();f.loseHealing=true;
            refused(h,IllegalStateException.class,()->f.at(B,100_000));near(h,f.player.getHealth(),3,"unknown receipt rolled back physical healing");
            near(h,f.state(B).resources().get(f.key()).value(),.7,"unknown receipt rolled back refund");
            near(h,f.state(A).retainedCosts().values().iterator().next().receipt().refundClaimed(),.3,"original issuer lost committed claim");
            h.assertValueEqual(f.state(B).equipment().get(f.holder),f.loadout,"unknown receipt rolled back migrated equipment");
            h.assertValueEqual(f.state(B).abilities().get(f.holder),f.abilities,"unknown receipt rolled back selected abilities");
            h.assertValueEqual(f.state(B).actors().get(f.holder),f.actor,"unknown receipt rolled back actor");
            h.assertTrue(f.state(A).sources().isEmpty()&&f.sessions.get(B).state().engine().pending().isPresent(),"unknown outcome lost committed migration or pending evidence");
            refused(h,IllegalStateException.class,f.host::checkpoint);refused(h,IllegalStateException.class,()->f.at(B,200_000));
            h.assertValueEqual(f.heals,1,"unknown world write retried");f.containers();
        }h.succeed();
    }
}
