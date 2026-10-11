package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.EffectSource;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import java.util.Set;
import net.minecraft.gametest.framework.GameTestHelper;

/** Imported contribution channels participate in the actual native damage pipeline. */
public class SharedStagesGameTest {
    @GameCase(environment="chorus_gametest:shared_stages_delay", maxTicks=30)
    public void storedSharedStageSurvivesSourceExpiryAndCombinesWithLiveVictimDefense(GameTestHelper h) throws Exception {
        var t=new DamageSnapshotComponentGameTest.Harness(h,true);
        try {
            t.control("init");t.control("save");var saved=t.saved();t.control("mark");t.detach();
            h.runAfterDelay(4,()-> {
                try(t) {
                    t.runtime.prepare();t.signal("test:stored_fire");
                    h.assertTrue(t.runtime.failure().isEmpty(),"shared stored attack failed");
                    near(h,t.receipts.getFirst().outgoing().orElseThrow().output().value(),6.75,"shared MAX then local conversion");
                    near(h,t.victim.getHealth(),89.875,"actual converted attack and current defense");
                    h.assertValueEqual(t.commands.getFirst().snapshot().orElseThrow(),saved,"pinned shared layout");h.succeed();
                }
            });
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:shared_stages_victim")
    public void localAndImportedBonusesShareOneGroupAndCurrentVictimProvidersRemainEligible(GameTestHelper h) throws Exception {
        try(var t=new DamageSnapshotComponentGameTest.Harness(h,true)) {
            t.runtime.bind(new EffectSource("local","test:local",t.origin.owner(),t.origin,Set.of()));
            t.control("init");t.control("save");t.runtime.unbind("local");
            var victim=t.victim.getUUID().toString();
            t.runtime.bind(new EffectSource("victim","test:victim",victim,new BuffInstance.Origin(victim,"victim","",""),Set.of()));
            t.control("fire");
            near(h,t.victim.getHealth(),92.575,"MAX(20%,35%) times live victim 10%, then local half scale");
            h.assertValueEqual(t.receipts.getFirst().outgoing().orElseThrow().trace().profile(),"test:derived","destination profile remains explicit");
            h.assertValueEqual(t.commands.getFirst().source(),t.origin,"original attribution retained");
            h.assertTrue(t.runtime.failure().isEmpty(),"shared native damage failed");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:shared_stages_unknown")
    public void unknownSharedDamageKeepsHealthAndSavedSnapshotWithoutReplay(GameTestHelper h) throws Exception {
        try(var t=new DamageSnapshotComponentGameTest.Harness(h,true)) {
            t.control("init");t.control("save");var saved=t.saved();t.failAfterDamage=true;boolean failed=false;
            try{t.control("fire");}catch(IllegalStateException expected){failed=true;}
            h.assertTrue(failed&&t.runtime.failure().isPresent(),"unknown damage must stop");
            near(h,t.victim.getHealth(),94,"committed shared damage");
            h.assertValueEqual(t.saved(),saved,"saved snapshot retained");t.failAfterDamage=false;t.runtime.prepare();
            h.assertValueEqual(t.commands.size(),1,"unknown shared damage replayed");
        }h.succeed();
    }
}
