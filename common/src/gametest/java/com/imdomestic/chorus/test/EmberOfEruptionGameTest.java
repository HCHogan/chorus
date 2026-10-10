package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.SolarGameTest.*;
import com.imdomestic.chorus.effect.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Eruption changes real query reach; delay and HP projection remain synthetic. */
public class EmberOfEruptionGameTest {
    static class Harness extends SolarGameTest.Harness {
        final List<LivingEntity> extra=new ArrayList<>();
        Harness(GameTestHelper h,double delay) throws Exception {
            super(h,false,delay,true,"ember_of_eruption");
            // Keep the ten-meter boundary fixtures in the known loaded chunk, independent of test placement.
            target.setPos(target.getX()-4,target.getY(),target.getZ());neighbor.setPos(target.getX()+2,target.getY(),target.getZ());
            fragment(a,"char","char");fragment(a,"ashes","ashes");
        }
        void fragment(EffectSource owner,String name,String instance) {
            runtime.bind(new EffectSource(instance,"chorus_d2:ember_of_"+name,owner.holder(),owner.origin(),Set.of()));
        }
        LivingEntity cow(double distance) {
            var e=h.spawnWithNoFreeWill(EntityTypes.COW,5,40,2);extra.add(e);
            e.setPos(target.getX()+distance,target.getY(),target.getZ());e.setNoGravity(true);
            e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500);e.setHealth(500);e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
            h.assertTrue(resolve(id(e))==e,"radius fixture is loaded");return e;
        }
        @Override public void close(){super.close();extra.forEach(LivingEntity::discard);}
    }
    @GameCase public void realEightAndTenMeterBoundariesControlDamageAndCharSpreadUsingOriginalOwner(GameTestHelper h)throws Exception {
        for(boolean original:List.of(false,true))try(var t=new Harness(h,0)) {
            t.fragment(original?t.a:t.b,"eruption","eruption");
            t.neighbor.setPos(t.target.getX()+9,t.target.getY(),t.target.getZ());
            var edge8=t.cow(8);var edge10=t.cow(10);var outside=t.cow(10.01);
            t.apply(t.a,60);t.apply(t.b,40);
            near(h,edge8.getHealth(),432.4,"eight meter boundary always hit");near(h,t.neighbor.getHealth(),original?32.4:100,"nine meter reach belongs to original owner");
            near(h,edge10.getHealth(),original?432.4:500,"inclusive ten meter boundary");near(h,outside.getHealth(),500,"outside expanded sphere");
            for(var e:List.of(edge8,t.neighbor,edge10)) {
                boolean hit=e==edge8||original;h.assertValueEqual(t.buff(SCORCH,e).isPresent(),hit,"Char uses actual expanded hits");
                if(hit)h.assertValueEqual(t.buff(SCORCH,e).orElseThrow().count(),60,"Eruption must not scale Char stacks");
            }
            h.assertTrue(t.buff(SCORCH,outside).isEmpty()&&t.buff(SCORCH,t.target).isEmpty(),"outside or center received Char");
            h.assertTrue(t.damage.stream().allMatch(d->d.source().equals(t.a.origin())&&d.proc().deny().isEmpty()),"original credit and chain eligibility preserved");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:eruption_delayed",maxTicks=40)
    public void delayedBlastKeepsCapturedRadiusAfterUnequippingAndQueriesCurrentMembers(GameTestHelper h)throws Exception {
        var t=new Harness(h,1);
        try {
            t.fragment(t.a,"eruption","eruption");t.neighbor.setPos(t.target.getX()+12,t.target.getY(),t.target.getZ());
            t.apply(t.a,60);t.apply(t.b,40);t.runtime.unbind("eruption");t.detach();
            h.runAfterDelay(10,()->{try{t.neighbor.setPos(t.target.getX()+9,t.target.getY(),t.target.getZ());}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(21,()->{try(t){
                t.runtime.prepare();h.assertTrue(t.runtime.failure().isEmpty(),"queued radius failed: "+t.runtime.failure());
                near(h,t.neighbor.getHealth(),32.4,"saved ten meter radius includes newly arrived target");
                h.assertValueEqual(t.buff(SCORCH,t.neighbor).orElseThrow().count(),60,"delayed Char uses expanded hit");
                h.assertValueEqual(t.damage.size(),2,"exactly one ignition");
                h.assertValueEqual(t.neighbor.getLastDamageSource().getEntity(),t.first,"detached original attacker");h.succeed();
            }});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
