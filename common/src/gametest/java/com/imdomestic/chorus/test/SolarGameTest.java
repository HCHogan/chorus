package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Shared Solar state and native execution. Curves in solar_test_calibration are synthetic. */
public class SolarGameTest {
    static final String SCORCH="chorus_d2:scorch", LOCKOUT="chorus_d2:scorch_lockout";
    static String id(LivingEntity e) { return e.getUUID().toString(); }
    static class Harness implements AutoCloseable {
        final GameTestHelper h; final LivingEntity first, second, target, neighbor;
        final EffectSource a,b; final MinecraftEffectRuntime runtime;
        final List<DamageCommand> damage=new ArrayList<>(); final List<DamageReceipt> receipts=new ArrayList<>(); final List<Long> times=new ArrayList<>();
        boolean fail;
        Harness(GameTestHelper h,boolean pvp) throws Exception { this(h,pvp,0,false); }
        Harness(GameTestHelper h,boolean pvp,double windup,boolean charFragments) throws Exception {
            this.h=h; first=h.spawnWithNoFreeWill(EntityTypes.COW,2,40,2); second=h.spawnWithNoFreeWill(EntityTypes.COW,3,40,2);
            if(pvp) { var player=h.makeMockServerPlayerInLevel(); player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket()); player.getAbilities().invulnerable=false; player.setInvulnerableTime(0); target=player; }
            else target=h.spawnWithNoFreeWill(EntityTypes.COW,4,40,2);
            neighbor=h.spawnWithNoFreeWill(EntityTypes.COW,5,40,2);
            double x=first.chunkPosition().getMinBlockX()+6.5,z=first.chunkPosition().getMinBlockZ()+6.5,y=first.getY();
            first.setPos(x,y+40,z); second.setPos(x+1,y+40,z); target.setPos(x,y,z); neighbor.setPos(x+2,y,z);
            for(var e:List.of(first,second,target,neighbor)) { e.setNoGravity(true); e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500); e.setHealth(500); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); h.assertTrue(resolve(id(e))==e,"fixture entity is loaded"); }
            target.setHealth(100); neighbor.setHealth(100); if(pvp) neighbor.addTag("chorus_d2:construct");
            a=source(first,"weapon"); b=source(second,"grenade");
            var fragments=new ArrayList<EffectProgram>();
            for(String name:List.of("solar","solar_test_calibration","solar_test_source")) {
                var json=ThreadedSpikeGameTest.json(name);
                if(name.equals("solar_test_calibration")) for(var entry:json.getAsJsonObject().getAsJsonArray("profiles")) {
                    var p=entry.getAsJsonObject();
                    if(p.get("id").getAsString().equals("chorus_d2:ignition_delay")) {
                        var coefficients=new com.google.gson.JsonArray(); coefficients.add(windup);
                        p.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("curve").add("coefficients",coefficients);
                    }
                }
                fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json).getOrThrow());
            }
            if(charFragments) fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("ember_of_char")).getOrThrow());
            var program=CompiledEffects.link(fragments); var state=EffectState.empty().withMode(pvp?EffectState.Mode.PVP:EffectState.Mode.PVE);
            for(var s:List.of(a,b)) state=state.withSource(s).withSource(new EffectSource(s.instance()+"-solar","chorus_d2:solar_scaling",s.holder(),s.origin(),Set.of()));
            var type=h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE,Identifier.parse("chorus_gametest:delayed")));
            var world=new MinecraftWorldActions(h.getLevel(),this::resolve,cmd->new DamageSource(type,null,resolve(cmd.source().owner())),(_,_) -> true,_ -> {});
            runtime=MinecraftEffectRuntime.install(h.getLevel(),program,state,new EffectClock((_,_) -> new EffectClock.Rate(0,List.of())),request -> {
                var result=world.apply(request);
                if(result instanceof DamageReceipt receipt) {
                    damage.add((DamageCommand)request.command()); receipts.add(receipt); times.add(state().buffs().timeMicros());
                    if(fail) throw new IllegalStateException("unknown after actual Solar damage");
                }
                return result;
            },MinecraftEffectRuntime::nativeSource);
        }
        EffectSource source(LivingEntity entity,String credit) { String owner=id(entity); return new EffectSource(owner,"test:scorch_source",owner,new BuffInstance.Origin(owner,owner,owner+"-weapon",owner+"-ability",Set.of("chorus:"+credit+"_damage")),Set.of()); }
        LivingEntity resolve(String value) { return h.getLevel().getEntity(UUID.fromString(value)) instanceof LivingEntity e?e:null; }
        EffectState state() { return runtime.state().engine().domain(); }
        Optional<BuffInstance> buff(String name,LivingEntity target) { return state().buffs().instances().values().stream().filter(v->v.definition().id().equals(name)&&v.key().holder().equals(id(target))).findFirst(); }
        void apply(EffectSource source,int stacks) { apply(source,target,stacks); }
        void apply(EffectSource source,LivingEntity victim,int stacks) { runtime.start(new RuleEngine.Signal("test:scorch_apply",new EffectEvent(source.holder(),id(victim),source.origin(),Set.of(),Map.of("stacks",new Measure(stacks,Unit.COUNT))))); h.assertTrue(runtime.failure().isEmpty(),"Solar runtime failed: "+runtime.failure()); }
        void detach() { runtime.unbind(a.instance()); runtime.unbind(a.instance()+"-solar"); }
        @Override public void close() { runtime.close(); for(var e:List.of(first,second,target,neighbor)) e.discard(); }
    }
    @GameCase(environment="chorus_gametest:solar_ticks",maxTicks=60)
    public void actualTicksKeepFirstSourceAfterReapplicationAndUnbindingWithoutRestartingCadence(GameTestHelper h) throws Exception {
        var t=new Harness(h,false);
        try {
            t.apply(t.a,30); t.detach();
            h.runAfterDelay(4,()-> { try { t.apply(t.b,30); } catch(Exception|Error e) {t.close();throw e;} });
            h.runAfterDelay(31,()-> { try(t) {
                t.runtime.prepare(); h.assertTrue(t.runtime.failure().isEmpty(),"tick runtime failed");
                h.assertValueEqual(t.times,List.of(500_000L,1_430_000L),"reapplication reset tick cadence");
                near(h,t.target.getHealth(),100-2*1.6236,"actual stacked Scorch damage");
                h.assertTrue(t.damage.stream().allMatch(d->d.source().equals(t.a.origin())&&d.killTags().equals(Set.of("chorus:weapon_kill"))),"first source or credit changed");
                h.assertValueEqual(t.target.getLastDamageSource().getEntity(),t.first,"native attacker remains the original source"); h.succeed();
            }});
        } catch(Exception|Error e) { t.close(); throw e; }
    }
    @GameCase public void mixedSourcesIgniteOnceWithActualNativeKillAndSharedTargetLockout(GameTestHelper h) throws Exception {
        try(var t=new Harness(h,false)) {
            t.neighbor.setHealth(50); t.apply(t.a,60); t.detach(); t.apply(t.b,40);
            h.assertTrue(t.buff(SCORCH,t.target).isEmpty()&&t.buff(LOCKOUT,t.target).isPresent(),"ignition state transition missing");
            near(h,t.target.getHealth(),32.4,"target actual ignition damage"); h.assertTrue(!t.neighbor.isAlive(),"nearby target should die from ignition");
            h.assertValueEqual(t.neighbor.getLastDamageSource().getEntity(),t.first,"native ignition kill attribution changed");
            h.assertValueEqual(t.receipts.stream().filter(r->r.deathId().isPresent()).count(),1L,"actual death receipt missing");
            h.assertTrue(t.damage.stream().allMatch(d->d.source().equals(t.a.origin())&&d.killTags().equals(Set.of("chorus:weapon_kill"))),"ignition inherited later source");
            t.apply(t.b,100); h.assertValueEqual(t.damage.size(),2,"lockout allowed another ignition"); h.assertTrue(t.state().timers().isEmpty(),"old Scorch timers survived ignition");
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:solar_guardian",maxTicks=40)
    public void explicitPvpNonlethalPolicyPreservesGuardianBeforeLethalIgnition(GameTestHelper h) throws Exception {
        var t=new Harness(h,true);
        try { t.target.setHealth(1.3f); t.apply(t.a,60);
            h.runAfterDelay(11,()-> {try(t) {
                t.runtime.prepare(); h.assertTrue(t.runtime.failure().isEmpty()&&t.receipts.size()==1&&t.receipts.getFirst().healthLoss()>0,"Scorch tick must cause real HP loss: "+t.receipts);
                near(h,t.target.getHealth(),1,"Scorch health floor"); h.assertTrue(t.target.isAlive(),"Scorch killed Guardian under nonlethal policy");
                t.apply(t.b,40); h.assertTrue(!t.target.isAlive(),"ignition must remain lethal"); near(h,t.neighbor.getHealth(),80,"construct damage uses its measured distance");
                h.assertTrue(t.receipts.stream().anyMatch(r->r.deathId().isPresent()),"ignition produced no actual death receipt"); h.succeed();
            }});
        } catch(Exception|Error e) {t.close();throw e;}
    }
    @GameCase public void allowedIgnitionChainDamagesBothTargetsTwiceWithoutGlobalSuppression(GameTestHelper h) throws Exception {
        try(var t=new Harness(h,false)) {
            t.target.setHealth(500);t.neighbor.setHealth(500);
            t.runtime.bind(new EffectSource("chain","test:solar_chain",t.a.holder(),t.a.origin(),Set.of())); t.apply(t.a,100);
            h.assertValueEqual(t.damage.size(),4,"allowed second ignition was suppressed"); near(h,t.target.getHealth(),364.8,"two actual ignitions on original target"); near(h,t.neighbor.getHealth(),364.8,"two actual ignitions on neighbor");
            h.assertTrue(t.buff(LOCKOUT,t.target).isPresent()&&t.buff(LOCKOUT,t.neighbor).isPresent(),"each ignited target owns its lockout");
            h.assertTrue(t.damage.stream().allMatch(d->d.proc().deny().isEmpty()),"global proc suppression added");
        } h.succeed();
    }
    @GameCase public void unknownIgnitionReceiptKeepsWorldDamageAndDoesNotRepeatExplosion(GameTestHelper h) throws Exception {
        try(var t=new Harness(h,false)) {
            t.fail=true; boolean failed=false;try { t.apply(t.a,100); } catch(IllegalStateException expected) {failed=true;}
            h.assertTrue(failed&&t.runtime.failure().isPresent(),"unknown result must stop runtime");
            near(h,t.target.getHealth()+t.neighbor.getHealth(),200-67.6,"one committed native damage action");
            h.assertTrue(t.buff(SCORCH,t.target).isEmpty()&&t.buff(LOCKOUT,t.target).isPresent(),"committed ignition state was rolled back");
            t.fail=false;t.runtime.prepare();h.assertValueEqual(t.damage.size(),1,"unknown ignition replayed");
        } h.succeed();
    }
}
