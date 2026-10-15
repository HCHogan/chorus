package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.platform.minecraft.MinecraftWorldActions;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Explicit core handoff with actual native transport and healing; automatic player-state migration is separate. */
public class BuffTransferGameTest {
    static final String A="test:departure",B="test:arrival";
    static CompiledEffects program() {
        return compile(object("""
            {"version":"buff-handoff-world","resources":[{"id":"test:energy","capacity":1,"initial":1}],
             "buffs":[{"definition":{"id":"test:live","version":"buff-handoff-world","duration":2},"bundle":"test:active"}],
             "bundles":[{"id":"test:input","rules":[{"id":"grant","on":"test:grant","do":[{"type":"chorus:grant_buff","buff":"test:live"}]}]},
               {"id":"test:active","scope":"buff","rules":[{"id":"gained","on":"chorus:buff_gained","if":{"type":"chorus:own_buff"},"do":[
                 {"action":{"type":"chorus:spend_resource","resource":"test:energy","payment":"cast","amount":{"type":"chorus:constant","value":0.6,"unit":"charge_fraction"}},"as":"paid"},
                 {"action":{"type":"chorus:retain_cost","cost":"paid","duration":{"type":"chorus:constant","value":5,"unit":"second"}},"as":"voucher"},
                 {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"do":[
                   {"type":"chorus:refund_retained_cost","cost":"voucher","fraction":{"type":"chorus:constant","value":0.5,"unit":"multiplier"}},
                   {"if":{"type":"chorus:own_buff"},"then":[{"type":"chorus:heal","amount":{"type":"chorus:constant","value":2,"unit":"damage"}}]},
                   {"type":"chorus:close_retained_cost","cost":"voucher"},
                   {"type":"chorus:refund_retained_cost","cost":"voucher","fraction":{"type":"chorus:constant","value":1,"unit":"multiplier"}}
                 ]}
               ]}]}]}
            """));
    }
    static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final ServerPlayer player;final String holder;final ServerLevel local,remote;
        final CompiledEffects p=program();final Map<String,EffectSession> sessions=new TreeMap<>();
        ResourceSessionAuthority host;boolean loseReceipt;int heals;
        Fixture(GameTestHelper h) {
            this.h=h;local=h.getLevel();remote=Objects.requireNonNull(local.getServer().getLevel(Level.NETHER));
            player=NativeMeleeGameTest.player(h);holder=player.getUUID().toString();player.setHealth(1);
            var origin=new BuffInstance.Origin(holder,"input","","");
            var initial=EffectState.empty().withBuffs(new BuffStore(10_000_000,1,Map.of()))
                    .withSource(new EffectSource("input","test:input",holder,origin,Set.of())).withResource(new ResourceState(key(),1,1,10_000_000));
            var arrival=EffectState.empty();
            // A live destination instance consumes generation 1, without replaying its gained rule.
            arrival=arrival.withBuffs(Buffs.grant(arrival.buffs(),p.buff("test:live"),"other","other",origin,1,1,2_000_000).store());
            sessions.put(A,new EffectSession(p,clock(),1,initial,r->execute(A,r)));
            sessions.put(B,new EffectSession(p,clock(),1,arrival,r->execute(B,r)));
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,Map.of(
                    A,new ResourceSessionAuthority.Binding(p,sessions.get(A)),B,new ResourceSessionAuthority.Binding(p,sessions.get(B))));
            host.claim(holder,A);sessions.get(A).start(10_000_000,new RuleEngine.Signal("test:grant",new EffectEvent(holder,holder,origin,Set.of(),Map.of())));
            sessions.get(A).observe(10_050_000,List.of());
        }
        ResourceState.Key key(){return new ResourceState.Key(holder,"test:energy");}
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request) {
            if(request.command() instanceof ResourceRequests.Request)return host.execute(domain,request);
            var level=domain.equals(A)?local:remote;
            var world=new MinecraftWorldActions(level,id->level.getEntity(UUID.fromString(id)) instanceof LivingEntity entity?entity:null,
                    _->level.damageSources().generic(),(_,_) -> true,_ -> {});
            var result=world.apply(request);h.assertTrue(result instanceof HealingReceipt,"unexpected world action");heals++;
            if(loseReceipt)throw new IllegalStateException("Lost receipt after relocated physical healing");return result;
        }
        void transfer() {
            var move=host.prepareTransferWithBuffs(holder,A,B);
            remote.getChunk(new BlockPos(8,90,8));
            player.teleport(new TeleportTransition(remote,new Vec3(8,90,8),Vec3.ZERO,0,0,TeleportTransition.DO_NOTHING));
            h.assertTrue(remote.getEntity(player.getUUID())==player&&local.getEntity(player.getUUID())==null,"native player did not transfer");
            host.commit(move);
        }
        void restore() {
            var saved=host.checkpoint();var domains=new TreeMap<String,EffectSession.Checkpoint>();
            saved.domains().forEach((domain,value)->domains.put(domain,EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(value))));
            var decoded=new ResourceSessionAuthority.Checkpoint(ResourceAuthorityCodec.decode(ResourceAuthorityCodec.encode(saved.directory())),domains);
            var result=ResourceSessionAuthority.restore(decoded,Map.of(
                    A,new ResourceSessionAuthority.RestoreBinding(p,clock(),1,r->execute(A,r)),
                    B,new ResourceSessionAuthority.RestoreBinding(p,clock(),1,r->execute(B,r))));
            host=result.authority();sessions.clear();result.bindings().forEach((domain,value)->sessions.put(domain,value.session()));
            h.assertValueEqual(host.checkpoint(),saved,"restore changed committed handoff");
        }
        @Override public void close(){local.getServer().getPlayerList().remove(player);player.discard();}
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void relocatedBuffHealsTheActualTransferredPlayerOnceAfterJointCoreRestore(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.transfer();f.restore();h.assertValueEqual(f.heals,0,"handoff or restore replayed healing");
            var buff=f.state(B).buffs().instances().values().stream().filter(v->v.key().holder().equals(f.holder)).findFirst().orElseThrow();
            h.assertValueEqual(buff.generation(),2L,"destination generation collision");h.assertValueEqual(buff.deadline(),1_950_000L,"buff duration reset");
            f.sessions.get(A).observe(10_200_000,List.of());near(h,f.player.getHealth(),1,"old domain still ran moved work");
            f.sessions.get(B).observe(49_999,List.of());near(h,f.player.getHealth(),1,"remaining delay shortened");
            f.sessions.get(B).observe(50_000,List.of());f.host.drain(20);near(h,f.player.getHealth(),3,"real arrival healing");
            near(h,f.state(B).resources().get(f.key()).value(),.7,"refund or closure used destination ledger");
            h.assertTrue(f.state(A).retainedCosts().isEmpty()&&f.state(A).buffs().instances().isEmpty(),"departure retained moved state or voucher");
            f.restore();f.sessions.get(B).observe(100_000,List.of());near(h,f.player.getHealth(),3,"post-write restore replayed healing");h.assertValueEqual(f.heals,1,"duplicate healing");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_runtime")
    public void unknownRelocatedHealingKeepsPhysicalHealthRefundAndIssuerClaimWithoutReplay(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.transfer();f.loseReceipt=true;refused(h,IllegalStateException.class,()->f.sessions.get(B).observe(50_000,List.of()));
            near(h,f.player.getHealth(),3,"unknown write rolled back native health");near(h,f.state(B).resources().get(f.key()).value(),.7,"unknown write rolled back refund");
            near(h,f.state(A).retainedCosts().values().iterator().next().receipt().refundClaimed(),.3,"unknown write lost original claim");
            h.assertTrue(f.sessions.get(B).state().engine().pending().isPresent(),"unknown operation lost pending evidence");
            refused(h,IllegalStateException.class,f.host::checkpoint);refused(h,IllegalStateException.class,()->f.sessions.get(B).observe(100_000,List.of()));
            h.assertValueEqual(f.heals,1,"unknown write retried");
        }h.succeed();
    }
}
