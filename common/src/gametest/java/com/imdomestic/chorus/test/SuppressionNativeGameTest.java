package com.imdomestic.chorus.test;

import com.google.gson.JsonArray;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;

/** Shared Compendium suppression content and real vanilla attacks; mob tiers are explicit test inputs. */
public class SuppressionNativeGameTest {
    static NativeRangedGameTest.Harness harness(GameTestHelper h){
        var data=ThreadedSpikeGameTest.json("suppression");var inputs=ThreadedSpikeGameTest.json("suppression_inputs");
        for(var entry:inputs.entrySet())if(!entry.getKey().equals("version")){
            if(!data.has(entry.getKey()))data.add(entry.getKey(),new JsonArray());entry.getValue().getAsJsonArray().forEach(data.getAsJsonArray(entry.getKey())::add);
        }
        return new NativeRangedGameTest.Harness(h,EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,data).getOrThrow());
    }
    static EffectSource caster(NativeRangedGameTest.Harness t){var caster=t.mob(EntityTypes.COW,12,2);var source=t.source(caster,"caster","test:suppression_inputs");t.runtime.bind(source);return source;}
    static void event(NativeRangedGameTest.Harness t,EffectSource caster,Mob target,String type){
        t.runtime.start(new RuleEngine.Signal("test:"+type,new EffectEvent(caster.holder(),target.getUUID().toString(),caster.origin(),Set.of(),Map.of())));
    }
    static boolean suppressed(NativeRangedGameTest.Harness t,Mob target){return t.runtime.state().engine().domain().buffs().instances().values().stream().anyMatch(b->b.definition().id().equals("chorus_d2:suppression")&&b.key().holder().equals(target.getUUID().toString()));}

    @GameCase(environment="chorus_gametest:suppression_native_tiers")
    public void nativeSuppressionUsesExplicitCurrentTierAndDoesNotDisableBossOrChampionShooting(GameTestHelper h){
        try(var t=harness(h)){
            var caster=caster(t);var victim=t.mobs.getFirst();
            for(String tier:List.of("rank_and_file","elite","champion","miniboss","boss","unclassified","guardian")){
                var skeleton=t.mob(EntityTypes.SKELETON,2,2);NativeRangedGameTest.bow(skeleton);
                if(!tier.equals("unclassified"))skeleton.addTag(tier.equals("guardian")?"chorus:guardian":"chorus_d2:"+tier);
                event(t,caster,skeleton,"suppress");h.assertTrue(suppressed(t,skeleton),"status was not applied: "+tier);skeleton.performRangedAttack(victim,1);
                boolean blocked=tier.equals("rank_and_file")||tier.equals("elite");h.assertValueEqual(t.shots(skeleton).isEmpty(),blocked,"tier shooting: "+tier);
                t.report(skeleton,blocked?MinecraftNativeActions.Outcome.RESTRICTED:MinecraftNativeActions.Outcome.ALLOWED);
                if(blocked){
                    h.assertValueEqual(t.runtime.nativeActionReport().get().decision().get().denials().getFirst().origin(),caster.origin(),"native suppression caster");
                    skeleton.addTag("chorus_d2:boss");skeleton.performRangedAttack(victim,1);h.assertValueEqual(t.shots(skeleton).size(),1,"current boss classification takes precedence");t.clearShots(skeleton);
                    skeleton.removeTag("chorus_d2:boss");skeleton.performRangedAttack(victim,1);h.assertTrue(t.shots(skeleton).isEmpty(),"current minor classification restored restriction");
                    event(t,caster,skeleton,"clear");skeleton.performRangedAttack(victim,1);h.assertValueEqual(t.shots(skeleton).size(),1,"cleanse restored native shot");
                }
                t.clearShots(skeleton);skeleton.discard();
            }t.settled();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:suppression_native_expiry",maxTicks=222)
    public void tenSecondPveSuppressionExpiresOnTheWorldClockAndRestoresNativeFire(GameTestHelper h){
        var t=harness(h);try{
            var caster=caster(t);var victim=t.mobs.getFirst();var skeleton=t.mob(EntityTypes.SKELETON,2,2);NativeRangedGameTest.bow(skeleton);skeleton.addTag("chorus_d2:elite");
            event(t,caster,skeleton,"suppress");skeleton.performRangedAttack(victim,1);h.assertTrue(t.shots(skeleton).isEmpty(),"newly suppressed elite fired");
            h.runAfterDelay(190,()->{try{skeleton.performRangedAttack(victim,1);h.assertTrue(suppressed(t,skeleton)&&t.shots(skeleton).isEmpty(),"ten-second restriction ended early");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(205,()->{try(t){skeleton.performRangedAttack(victim,1);h.assertTrue(!suppressed(t,skeleton),"suppression did not expire");h.assertValueEqual(t.shots(skeleton).size(),1,"expiry restored actual native arrow");t.report(skeleton,MinecraftNativeActions.Outcome.ALLOWED);t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
