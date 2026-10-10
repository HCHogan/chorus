package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.StatusResult;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Documented source-specific timing; producer geometry/projectiles are not simulated here. */
class DuranceTest {
    static CompiledEffects program() throws Exception {
        return link("slow","stasis_duration","durance","character_stats","freeze","freeze_test_falloff",
                "combat_damage","movement_attributes","weapon_stats","slow_inputs","durance_inputs");
    }
    static EffectSource fragment(String id,String holder) {
        return new EffectSource(id,"chorus_d2:durance",holder,new BuffInstance.Origin(holder,id,"",""),Set.of());
    }
    static EffectSource producer(String id,String holder,double base,double extension) {
        var source=SlowTest.source(id,holder,base);var parameters=new HashMap<>(source.parameters());
        parameters.put("slow_durance_extension",new Measure(extension,Unit.SECOND));
        return new EffectSource(id,source.bundle(),holder,source.origin(),Set.of(),parameters);
    }
    static void bind(SlowTest.Harness h,EffectSource source) {h.session.start(h.state().buffs().timeMicros(),SourceChange.bind(source));}
    static void remove(SlowTest.Harness h,String id) {h.session.start(h.state().buffs().timeMicros(),SourceChange.remove(id));}
    static long micros(double seconds) {return Math.round(seconds*1_000_000);}

    @Test void documentedSlowExtensionsDependOnProducerAndOnlyTheAppliersFragmentCounts() throws Exception {
        // Withering Blade, Bleak Watcher and Duskfield respectively, combatant then Guardian.
        for(double[] timing:List.of(new double[]{3.5,3.5,0},new double[]{1.5,.5,1},new double[]{4.5,4.5,0},
                new double[]{2,1.75,1},new double[]{2,2,0},new double[]{2,2,1})) {
            var h=new SlowTest.Harness(timing[2]==1,true,program());var s=producer("producer","caster",timing[0],timing[1]);bind(h,s);
            bind(h,fragment("recipient","target"));bind(h,fragment("ally","other"));h.apply(s,1);
            assertEquals(micros(timing[0]),h.checks.getLast().duration());h.event(s,"clear_slow",0);
            bind(h,fragment("own","caster"));bind(h,fragment("duplicate","caster"));h.apply(s,1);
            assertEquals(micros(timing[0]+timing[1]),h.checks.getLast().duration());
            assertEquals(micros(timing[0]+timing[1]),h.buff(SlowTest.S).orElseThrow().deadline());
            assertEquals(s.origin(),h.buff(SlowTest.S).orElseThrow().origin());
        }
    }
    @Test void unequippingDoesNotResizeCommittedSlowAndShorterRefreshPreservesItsDeadline() throws Exception {
        var h=new SlowTest.Harness(true,true,program());var s=producer("blade","caster",1.5,.5);bind(h,s);bind(h,fragment("own","caster"));h.apply(s,40);
        remove(h,"own");h.until(100_000);h.apply(s,10);assertEquals(1_500_000,h.checks.getLast().duration());
        assertEquals(2_000_000,h.buff(SlowTest.S).orElseThrow().deadline());assertEquals(50,h.buff(SlowTest.S).orElseThrow().count());
        h.until(1_999_999);assertTrue(h.buff(SlowTest.S).isPresent());h.until(2_000_000);assertTrue(h.buff(SlowTest.S).isEmpty());
        h.apply(s,1);assertEquals(3_500_000,h.buff(SlowTest.S).orElseThrow().deadline());
    }
    @Test void newlyEquippedFragmentExtendsNextRefreshWithoutTakingOverTheFirstAppliersCredit() throws Exception {
        var h=new SlowTest.Harness(false,true,program());var a=producer("first","caster",2,2);var b=producer("second","other",2,2);bind(h,a);bind(h,b);
        h.apply(a,20);bind(h,fragment("own","other"));h.until(1_000_000);h.apply(b,20);
        assertEquals(5_000_000,h.buff(SlowTest.S).orElseThrow().deadline());assertEquals(a.origin(),h.buff(SlowTest.S).orElseThrow().origin());
    }
    @Test void hundredStackConversionDoesNotExtendFreezeAndDeniedSlowStillRequiresAuthorization() throws Exception {
        var h=new SlowTest.Harness(true,true,program());var s=producer("blade","caster",1.5,.5);bind(h,s);bind(h,fragment("own","caster"));
        h.slowDecision=StatusResult.Decision.DENIED;h.apply(s,100);assertEquals(2_000_000,h.checks.getLast().duration());assertTrue(h.buff(SlowTest.S).isEmpty());assertTrue(h.buff(SlowTest.F).isEmpty());
        h.slowDecision=StatusResult.Decision.ALLOWED;h.apply(s,100);assertTrue(h.buff(SlowTest.S).isEmpty());
        assertEquals(1_350_000,h.buff(SlowTest.F).orElseThrow().deadline());assertEquals(s.origin(),h.buff(SlowTest.F).orElseThrow().origin());
    }
    @Test void lingeringLifetimesUseTheirOwnExtensionsAndUnequippingLeavesExistingTimersAlone() throws Exception {
        for(double[] timing:List.of(new double[]{7,2},new double[]{25,5})) {
            var p=program();var source=new EffectSource("field","test:durance_lingering_inputs","caster",new BuffInstance.Origin("caster","field","",""),Set.of(),Map.of("duration",new Measure(timing[0],Unit.SECOND),"extension",new Measure(timing[1],Unit.SECOND)));
            var session=new EffectSession(engine(p),EffectState.empty().withSource(source).withSource(fragment("own","caster")),r->{throw new AssertionError(r.command());});
            session.start(0,new RuleEngine.Signal("test:start_lingering",new EffectEvent("caster","target",source.origin(),Set.of(),Map.of(),Map.of(),Map.of("source_instance",source.instance(),"bundle",source.bundle()))));
            long end=micros(timing[0]+timing[1]);assertEquals(end,buff(session.state(),"test:durance_lingering","caster").deadline());session.start(0,SourceChange.remove("own"));
            session.observe(end-1,List.of());assertEquals(end,buff(session.state(),"test:durance_lingering","caster").deadline());session.observe(end,List.of());assertTrue(session.state().engine().domain().buffs().instances().isEmpty());
        }
    }
    @Test void unrelatedDurationsAreUntouchedAndMissingQualifiedExtensionsAreNotGuessed() throws Exception {
        var p=program();var f=fragment("own","caster");var state=EffectState.empty().withSource(f);
        for(String tag:List.of("chorus_d2:freeze","chorus_d2:stasis","chorus_d2:class_ability")) {
            var query=new EffectEvent("caster","target",f.origin(),Set.of(tag),Map.of());
            assertEquals(6,p.calculate(state,"caster",query,"chorus_d2:stasis_duration",new Measure(6,Unit.SECOND),List.of()).output().value());
        }
        var qualified=new EffectEvent("caster","target",f.origin(),Set.of("chorus_d2:slow"),Map.of());
        assertThrows(RuntimeException.class,()->p.calculate(state,"caster",qualified,"chorus_d2:stasis_duration",new Measure(6,Unit.SECOND),List.of()));
    }
    @Test void meleeBonusDeduplicatesAndClampsIndependentlyOfDuration() throws Exception {
        var p=program();var f=fragment("own","caster");var state=EffectState.empty().withSource(f).withSource(fragment("duplicate","caster")).withSource(fragment("ally","other"));
        var query=new EffectEvent("caster","",f.origin(),Set.of(),Map.of());
        for(double base:List.of(80d,195d))assertEquals(Math.min(200,base+10),p.calculate(state,"caster",query,"chorus_d2:melee_stat",new Measure(base,Unit.STAT_POINT),List.of()).output().value());
        assertEquals(80,p.calculate(state,"target",query,"chorus_d2:melee_stat",new Measure(80,Unit.STAT_POINT),List.of()).output().value());
    }
    @Test void explicitZeroIsUnaffectedWhileMissingOrNegativeSlowCalibrationCannotApply() throws Exception {
        var h=new SlowTest.Harness(false,true,program());bind(h,fragment("own","caster"));var s=producer("zero","caster",2,0);bind(h,s);h.apply(s,1);assertEquals(2_000_000,h.checks.getLast().duration());h.event(s,"clear_slow",0);
        var negative=producer("negative","caster",2,-1);bind(h,negative);int before=h.checks.size();h.apply(negative,1);assertEquals(before,h.checks.size());assertTrue(h.buff(SlowTest.S).isEmpty());
        var missing=new HashMap<>(s.parameters());missing.remove("slow_durance_extension");assertThrows(IllegalArgumentException.class,()->h.p.validateSource(new EffectSource("missing",s.bundle(),s.holder(),s.origin(),Set.of(),missing)));
    }
}
