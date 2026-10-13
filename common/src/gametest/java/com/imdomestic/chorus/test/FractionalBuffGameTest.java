package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import net.minecraft.gametest.framework.GameTestHelper;

/** Fractional shared Scorch through native authorization, damage, player classification and server timers. */
public class FractionalBuffGameTest {
    @GameCase(environment="chorus_gametest:fractional_threshold",maxTicks=50)
    public void ninetyNineAndAHalfDoesNotIgniteUntilTheLastHalfArrives(GameTestHelper h)throws Exception {
        var t=new SolarGameTest.Harness(h,false);try {
            t.apply(t.a,21.5);t.apply(t.b,78);near(h,t.buff(SolarGameTest.SCORCH,t.target).orElseThrow().quantity(),99.5,"exact fractional accumulation");h.assertTrue(t.damage.isEmpty(),"storage slot count caused early ignition");
            h.runAfterDelay(11,()->{try(t){h.assertTrue(t.runtime.failure().isEmpty(),"fractional DoT failed");near(h,t.target.getHealth(),100-(2.7+.175*99.5)*1.23*.1,"fractional native DoT");t.apply(t.b,.5);h.assertTrue(t.buff(SolarGameTest.SCORCH,t.target).isEmpty()&&t.buff(SolarGameTest.LOCKOUT,t.target).isPresent(),"exact threshold did not ignite");near(h,t.neighbor.getHealth(),32.4,"native ignition damage");h.assertTrue(t.damage.stream().allMatch(c->c.source().equals(t.a.origin())),"fractional reapplication stole first-source credit");h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:fractional_tail",maxTicks=80)
    public void subunitScorchIsPresentDamagesAndDecaysWithoutInventingAWholeStack(GameTestHelper h)throws Exception {
        var t=new SolarGameTest.Harness(h,false);try {
            t.apply(t.a,.5);var before=t.buff(SolarGameTest.SCORCH,t.target).orElseThrow();near(h,before.quantity(),.5,"subunit quantity");h.assertValueEqual(before.count(),1,"one storage slot");near(h,BuffObservation.capture(t.state().buffs(),SolarGameTest.id(t.target)).require(SolarGameTest.id(t.target)).getFirst().stacks(),.5,"observed gameplay quantity");
            h.runAfterDelay(11,()->{try {h.assertTrue(t.runtime.failure().isEmpty(),"subunit status was treated as absent");near(h,t.target.getHealth(),100-(2.7+.175*.5)*1.2*.1,"half-stack damage");}catch(Exception|Error e){t.close();throw e;}});h.runAfterDelay(61,()->{try(t){h.assertTrue(t.runtime.failure().isEmpty(),"fractional decay failed");h.assertTrue(t.buff(SolarGameTest.SCORCH,t.target).isEmpty()&&t.buff(SolarGameTest.LOCKOUT,t.target).isEmpty(),"tail did not expire or incorrectly ignited");h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:fractional_guardian",maxTicks=30)
    public void actualGuardianReceivesSevenAndAHalfStackGrantsWithoutRounding(GameTestHelper h)throws Exception {
        try(var t=new SolarGameTest.Harness(h,true)){
            for(int i=0;i<13;i++)t.apply(t.b,7.5);near(h,t.buff(SolarGameTest.SCORCH,t.target).orElseThrow().quantity(),97.5,"guardian exact thirteen applications");h.assertTrue(t.damage.isEmpty(),"guardian rounded into early ignition");t.apply(t.b,2.5);h.assertTrue(t.buff(SolarGameTest.LOCKOUT,t.target).isPresent(),"guardian threshold missing");h.assertTrue(t.damage.stream().allMatch(c->c.source().equals(t.b.origin())),"guardian source credit");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:fractional_serialization",maxTicks=30)
    public void nativeFractionalStateSerializesAllQuantityAndSourceFields(GameTestHelper h)throws Exception {
        try(var t=new SolarGameTest.Harness(h,false)){
            t.apply(t.a,21.5);t.apply(t.b,7.5);var actual=t.buff(SolarGameTest.SCORCH,t.target).orElseThrow();var codec=EffectCheckpointCodec.DATA;var loaded=codec.decode(codec.encode(t.state().buffs()),BuffStore.class);var restored=loaded.active(actual.key()).orElseThrow();h.assertValueEqual(restored,actual,"native state storage round trip");near(h,restored.quantity(),29,"stored quantity");h.assertValueEqual(restored.origin(),t.a.origin(),"stored first source");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:fractional_unknown",maxTicks=35)
    public void unknownFractionalNativeTickKeepsAppliedDamageAndDoesNotReplay(GameTestHelper h)throws Exception {
        var t=new SolarGameTest.Harness(h,false);try {
            t.apply(t.a,21.5);t.fail=true;h.runAfterDelay(12,()->{try(t){h.assertTrue(t.runtime.failure().isPresent(),"unknown tick did not stop runtime");near(h,t.target.getHealth(),100-(2.7+.175*21.5)*1.2*.1,"committed fractional HP loss");near(h,t.buff(SolarGameTest.SCORCH,t.target).orElseThrow().quantity(),21.5,"committed stack quantity");MinecraftEffectRuntime.tick(h.getLevel());h.assertValueEqual(t.damage.size(),1,"fractional damage replayed");h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
