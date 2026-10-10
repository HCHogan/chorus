package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Real status authorization, source ownership and native movement restoration. */
public class DuranceGameTest {
    static CompiledEffects program() {
        var parts=new ArrayList<EffectProgram>();parts.add(SlowGameTest.program().program());
        for(String name:List.of("durance","character_stats"))parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json(name)).getOrThrow());
        return CompiledEffects.link(parts);
    }
    static EffectSource fragment(String id,LivingEntity holder) {
        String owner=holder.getUUID().toString();return new EffectSource(id,"chorus_d2:durance",owner,new BuffInstance.Origin(owner,id,"",""),Set.of());
    }
    static EffectSource source(String id,LivingEntity caster,double base,double extension) {
        var slow=SlowGameTest.source(caster,base);var params=new HashMap<>(slow.parameters());params.put("slow_durance_extension",new Measure(extension,Unit.SECOND));
        return new EffectSource(id,slow.bundle(),slow.holder(),slow.origin(),Set.of(),params);
    }
    @GameCase(environment="chorus_gametest:durance_sources")
    public void actualGuardianAndCombatantSlowUseDifferentSourceExtensionsAndOnlyApplierEquipment(GameTestHelper h) {
        try(var t=new FreezeGameTest.Harness(h,program(),EffectState.empty())) {
            var caster=t.mob(EntityTypes.COW,12,2,"");var player=t.player(2);var npc=t.mob(EntityTypes.COW,4,2,"elite");
            var blade=source("blade",caster,1.5,.5);var turret=source("turret",caster,4.5,4.5);t.runtime.bind(blade);t.runtime.bind(turret);
            t.runtime.bind(fragment("recipient",player));t.runtime.bind(fragment("ally",npc));SlowGameTest.apply(t,blade,player,40);
            h.assertValueEqual(t.checks.getLast().duration(),1_500_000L,"recipient extended hostile Slow");SlowGameTest.clear(t,blade,player);
            t.runtime.bind(fragment("own",caster));t.runtime.bind(fragment("duplicate",caster));SlowGameTest.apply(t,blade,player,40);
            h.assertValueEqual(t.checks.getLast().duration(),2_000_000L,"Guardian Blade extension");
            SlowGameTest.apply(t,turret,npc,20);h.assertValueEqual(t.checks.getLast().duration(),9_000_000L,"combatant turret extension");
            h.assertValueEqual(SlowGameTest.slow(t,npc).orElseThrow().origin(),turret.origin(),"fragment stole Slow credit");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:durance_expiry",maxTicks=60)
    public void removingFragmentKeepsCommittedDeadlineAndNativeSpeedRestoresAtExtendedExpiry(GameTestHelper h) {
        var t=new FreezeGameTest.Harness(h,program(),EffectState.empty());try {
            var caster=t.mob(EntityTypes.COW,12,2,"");var player=t.player(2);var blade=source("blade",caster,1.5,.5);t.runtime.bind(blade);t.runtime.bind(fragment("own",caster));
            double base=player.getAttributeValue(Attributes.MOVEMENT_SPEED);SlowGameTest.apply(t,blade,player,40);long deadline=SlowGameTest.slow(t,player).orElseThrow().deadline();
            h.runAfterDelay(2,()->{try {
                t.runtime.unbind("own");SlowGameTest.apply(t,blade,player,10);h.assertValueEqual(t.checks.getLast().duration(),1_500_000L,"new application retained unequipped bonus");
                h.assertValueEqual(SlowGameTest.slow(t,player).orElseThrow().deadline(),deadline,"shorter refresh changed deadline");
            }catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(35,()->{try {
                t.runtime.prepare();h.assertTrue(SlowGameTest.slow(t,player).isPresent(),"committed extended Slow expired early");near(h,player.getAttributeValue(Attributes.MOVEMENT_SPEED),base*.5,"Slow penalty missing");
            }catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(45,()->{try(t) {
                t.runtime.prepare();h.assertTrue(SlowGameTest.slow(t,player).isEmpty(),"extended Slow did not expire");near(h,player.getAttributeValue(Attributes.MOVEMENT_SPEED),base,"speed not restored");t.settled();h.succeed();
            }});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:durance_conversion")
    public void extendedSlowStillNeedsNativeAuthorizationAndItsFreezeConversionRetainsIndependentTiming(GameTestHelper h) {
        try(var t=new FreezeGameTest.Harness(h,program(),EffectState.empty())) {
            var caster=t.player(12);var target=t.player(2);var blade=source("blade",caster,1.5,.5);t.runtime.bind(blade);t.runtime.bind(fragment("own",caster));
            t.deniedStatus="chorus_d2:slow";SlowGameTest.apply(t,blade,target,100);h.assertValueEqual(t.checks.getLast().duration(),2_000_000L,"authorization did not see calculated duration");
            h.assertTrue(SlowGameTest.slow(t,target).isEmpty()&&t.status(target).isEmpty(),"denied Slow converted");
            t.deniedStatus="";SlowGameTest.apply(t,blade,target,100);h.assertTrue(SlowGameTest.slow(t,target).isEmpty()&&t.status(target).isPresent(),"authorized conversion failed");
            h.assertValueEqual(t.checks.getLast().duration(),1_350_000L,"Durance extended Freeze");h.assertValueEqual(NativeMovementGameTest.mask(target),15,"Freeze controls missing");t.settled();
        }h.succeed();
    }
}
