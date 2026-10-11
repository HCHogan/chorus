package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

/** Generic count queries and count-to-time transformations drive real scheduled healing and native damage. */
public class ProfileValueGameTest {
    @GameCase(environment="chorus_gametest:profile_value_delay",maxTicks=30)
    public void countDerivedDelayRetainsCapturedHealingAndReadsTheCurrentOwnerAfterSourcesAreRemoved(GameTestHelper h)throws Exception {
        var t=new AttributeQueryGameTest.Harness(h,"profile_values");
        try {
            t.runtime.bind(t.source("foreign","single",t.target));t.signal("test:later");
            near(h,t.owner.getHealth(),10,"query and count-derived schedule have not healed yet");
            t.runtime.unbind("boost");t.runtime.unbind("driver");
            h.runAfterDelay(1,()->{try{t.runtime.prepare();near(h,t.owner.getHealth(),10,"two copies convert to a 0.1 second delay");}catch(Throwable error){t.close();throw error;}});
            h.runAfterDelay(3,()->{try(t){t.runtime.prepare();near(h,t.owner.getHealth(),16,"six captured healing and zero current owner count");near(h,t.target.getHealth(),100,"foreign sources do not become owner benefits");h.assertTrue(t.runtime.failure().isEmpty()&&t.runtime.state().idle(),"healthy delayed query");h.succeed();}});
        }catch(Throwable error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:profile_value_damage")
    public void nativeDamageKeepsCapturedOwnerCopiesAndRechecksEachVictimsCurrentCopies(GameTestHelper h)throws Exception {
        try(var t=new AttributeQueryGameTest.Harness(h,"profile_values")){
            t.attack=t.runtime.program().captureDamage(t.runtime.state().engine().domain(),new DamageCommand("unknown",t.origin,10,"minecraft:generic",Set.of(),Set.of(),false,Optional.of("test:damage")));
            t.runtime.unbind("boost");t.runtime.unbind("attack");t.runtime.bind(t.source("victim","single",t.target));
            near(h,t.hit().outgoing().orElseThrow().output().value(),13,"two owner copies at capture and one current victim copy");near(h,t.target.getHealth(),87,"native health reflects the queried multiplier");
            t.runtime.unbind("victim");near(h,t.hit().outgoing().orElseThrow().output().value(),12,"victim copies re-read independently of the frozen owner");near(h,t.target.getHealth(),75,"second actual native hit");
        }h.succeed();
    }
}
