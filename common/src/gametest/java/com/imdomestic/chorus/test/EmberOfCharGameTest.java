package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.SolarGameTest.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Real damage with Compendium Char/Ashes stacks and explicitly synthetic ignition windup. */
public class EmberOfCharGameTest {
    // Native health is float: accumulate the expected rounding across repeated 67.6 damage hits.
    static float healthAfterWaves(int waves) { float hp=1000; for(int i=0;i<waves*2;i++) hp-=67.6f; return hp; }
    static class Harness extends SolarGameTest.Harness {
        final List<LivingEntity> targets;
        Harness(GameTestHelper h,double delay,boolean four,boolean ashes) throws Exception {
            super(h,false,delay,true);
            var all=new ArrayList<>(List.of(target,neighbor));
            if(four) for(int i=0;i<2;i++) {
                var e=h.spawnWithNoFreeWill(EntityTypes.COW,4+i,40,2);
                e.setPos(target.getX()+i*2,target.getY(),target.getZ()+2); e.setNoGravity(true);
                e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
                h.assertTrue(resolve(id(e))==e,"extra target is loaded"); all.add(e);
            }
            targets=List.copyOf(all);
            for(var e:targets) { e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); e.setHealth(1000); }
            fragment("char"); if(ashes)fragment("ashes");
        }
        void fragment(String name) { runtime.bind(new EffectSource(a.holder()+"-"+name,"chorus_d2:ember_of_"+name,a.holder(),a.origin(),Set.of())); }
        void healthy() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty(),"Char runtime failed: "+runtime.failure()); }
        @Override public void close() { super.close(); for(var e:targets) e.discard(); }
    }
    @GameCase(environment="chorus_gametest:char_chain",maxTicks=160)
    public void fourRealTargetsKeepIgnitingAcrossFiveWavesAndStopWhenCharIsRemoved(GameTestHelper h) throws Exception {
        var t=new Harness(h,1,true,true);
        try {
            t.apply(t.a,t.target,100); t.apply(t.a,t.neighbor,100);
            h.runAfterDelay(101,()-> {try {
                t.healthy(); h.assertValueEqual(t.damage.size(),40,"five waves of two ignitions hitting four targets");
                for(var e:t.targets) {
                    near(h,e.getHealth(),healthAfterWaves(5),"five waves of native damage");
                    h.assertValueEqual(e.getLastDamageSource().getEntity(),t.first,"original native attacker persists across waves");
                }
                h.assertValueEqual(t.damage.stream().map(DamageCommand::target).distinct().count(),4L,"all four targets participate");
                h.assertTrue(t.damage.stream().allMatch(d->d.source().equals(t.a.origin())&&d.killTags().equals(Set.of("chorus:weapon_kill"))&&d.proc().deny().isEmpty()),"chain credit or proc policy changed");
                h.assertTrue(t.receipts.stream().allMatch(r->r.healthLoss()>0),"chain did not cause real health loss");
                t.runtime.unbind(t.a.holder()+"-char");
            }catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(141,()-> {try(t) {
                t.healthy(); h.assertValueEqual(t.damage.size(),48,"queued sixth wave completes; no seventh wave");
                for(var e:t.targets) near(h,e.getHealth(),healthAfterWaves(6),"six completed damage waves");
                h.assertTrue(t.state().timers().isEmpty(),"chain timers remained after Char removal"); h.succeed();
            }});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:char_center",maxTicks=60)
    public void delayedExplosionExcludesItsOriginalCenterAfterLockoutAndKeepsFrozenDamage(GameTestHelper h) throws Exception {
        var t=new Harness(h,2,false,false);
        try {
            t.apply(t.a,100); t.detach();
            h.runAfterDelay(42,()-> {try(t) {
                t.healthy(); h.assertValueEqual(t.damage.size(),2,"detached delayed explosion completed");
                h.assertTrue(t.buff(LOCKOUT,t.target).isEmpty()&&t.buff(SCORCH,t.target).isEmpty(),"center exclusion depends incorrectly on lockout");
                h.assertValueEqual(t.buff(SCORCH,t.neighbor).orElseThrow().count(),40,"Char's real stack count");
                h.assertValueEqual(t.buff(SCORCH,t.neighbor).orElseThrow().origin(),t.a.origin(),"fragment replaced original attribution");
                near(h,t.target.getHealth(),932.4,"frozen payload survives unbinding"); near(h,t.neighbor.getHealth(),932.4,"nearby native damage"); h.succeed();
            }});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase public void actualIgnitionKillCannotStartScorchOnDeadNeighbor(GameTestHelper h) throws Exception {
        try(var t=new Harness(h,0,false,true)) {
            t.neighbor.setHealth(50); t.apply(t.a,100); t.healthy();
            h.assertTrue(!t.neighbor.isAlive(),"neighbor must actually die");
            h.assertTrue(t.buff(SCORCH,t.neighbor).isEmpty()&&t.buff(SCORCH,t.target).isEmpty(),"dead neighbor or original center received Char");
            h.assertTrue(t.receipts.stream().anyMatch(r->r.deathId().isPresent()),"missing real death receipt");
            h.assertTrue(t.state().timers().isEmpty(),"dead victim started a Solar timer");
        } h.succeed();
    }
}
