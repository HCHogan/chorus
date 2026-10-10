package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static com.imdomestic.chorus.effect.SolarTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Documented radius with synthetic world projection and delay. */
class EmberOfEruptionTest {
    static CompiledEffects program(double delay) throws Exception {
        return CompiledEffects.link(List.of(EmberOfCharTest.program(delay).program(),
                EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("ember_of_eruption")).getOrThrow()));
    }
    static class Harness extends SolarTest.Harness {
        Harness(double delay,EffectState.Mode mode) throws Exception {super(program(delay),mode);}
        void fragment(EffectSource owner,String name,String instance) {
            session.start(state().buffs().timeMicros(),SourceChange.bind(new EffectSource(instance,"chorus_d2:ember_of_"+name,owner.holder(),owner.origin(),Set.of())));healthy();
        }
        void eruption(EffectSource owner) {fragment(owner,"eruption",owner.holder()+"-eruption");}
        void remove(String instance) {session.start(state().buffs().timeMicros(),SourceChange.remove(instance));healthy();}
        @Override RuleEngine.ActionResult execute(RuleEngine.WorldRequest request) {
            if(request.command() instanceof TargetQuery q) {
                queries.add(q);return new TargetQuery.Result(q,TargetQuery.Outcome.AVAILABLE,
                        targets.stream().filter(t->t.distance()<=q.radius()).sorted(q.comparator()).toList());
            }
            return super.execute(request);
        }
    }
    @Test void documentedRadiusAppliesInBothModesAndDuplicateFragmentDoesNotStack() throws Exception {
        for(var mode:EffectState.Mode.values()) for(boolean equipped:List.of(false,true)) {
            var h=new Harness(0,mode);
            if(equipped){h.eruption(FIRST);h.fragment(FIRST,"eruption","duplicate");}
            h.apply(0,FIRST,100);assertEquals(equipped?10:8,h.queries.getFirst().radius());
            if(equipped){h.remove("first-eruption");h.apply(1_600_000,FIRST,100);assertEquals(10,h.queries.getLast().radius());
                h.remove("duplicate");h.apply(3_200_000,FIRST,100);assertEquals(8,h.queries.getLast().radius());}
        }
        var p=program(0);assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
    }
    @Test void radiusBelongsToFirstScorchOwnerNotFinisherOrIgnitedTarget() throws Exception {
        for(boolean original:List.of(false,true)) {
            var h=new Harness(0,EffectState.Mode.PVE);h.eruption(original?FIRST:SECOND);
            h.eruption(source("target","weapon"));h.apply(0,FIRST,60);h.apply(0,SECOND,40);
            assertEquals(original?10:8,h.queries.getFirst().radius());
            assertTrue(h.damage.stream().allMatch(d->d.source().equals(FIRST.origin())));
        }
    }
    @Test void queuedIgnitionKeepsThresholdRadiusAndNextGenerationSamplesAgain() throws Exception {
        for(boolean initiallyEquipped:List.of(false,true)) {
            var h=new Harness(1,EffectState.Mode.PVE);if(initiallyEquipped)h.eruption(FIRST);
            h.apply(0,FIRST,100);h.until(500_000);
            if(initiallyEquipped)h.remove("first-eruption");else h.eruption(FIRST);
            h.detach(FIRST);h.until(1_000_000);assertEquals(initiallyEquipped?10:8,h.queries.getFirst().radius());
            h.session.start(1_000_000,SourceChange.bind(FIRST));
            h.session.start(1_000_000,SourceChange.bind(new EffectSource("first-solar","chorus_d2:solar_scaling",FIRST.holder(),FIRST.origin(),Set.of())));
            h.apply(1_600_000,FIRST,100);h.until(2_600_000);assertEquals(initiallyEquipped?8:10,h.queries.getLast().radius());
        }
    }
    @Test void extendedSpherePropagatesRealCharStacksAtTenMetersWithoutIncreasingDamageOrStacks() throws Exception {
        for(boolean equipped:List.of(false,true)) {
            var h=new Harness(0,EffectState.Mode.PVE);h.fragment(FIRST,"char","char");h.fragment(FIRST,"ashes","ashes");if(equipped)h.eruption(FIRST);
            var ids=List.of("target","edge8","between","edge10","outside");double[] distances={0,8,9,10,10.001};
            var members=new ArrayList<TargetQuery.Target>();for(int i=0;i<ids.size();i++){members.add(new TargetQuery.Target(ids.get(i),distances[i]));h.views.put(ids.get(i),view(false,false));}h.targets=members;
            h.apply(0,FIRST,100);assertEquals(equipped?4:2,h.damage.size());h.amounts.forEach(v->assertEquals(67.6,v,1e-9));
            var scorched=h.state().buffs().instances().values().stream().filter(b->b.definition().id().equals(SCORCH)).toList();
            assertEquals(equipped?Set.of("edge8","between","edge10"):Set.of("edge8"),scorched.stream().map(b->b.key().holder()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(scorched.stream().allMatch(b->b.count()==60&&b.origin().equals(FIRST.origin())));
            assertTrue(h.damage.stream().allMatch(d->d.proc().deny().isEmpty()));
        }
    }
    @Test void pvpExtendedReachStillRequiresPositiveFalloffDamageBeforeCharCanSpread() throws Exception {
        var h=new Harness(0,EffectState.Mode.PVP);h.eruption(FIRST);h.fragment(FIRST,"char","char");h.fragment(FIRST,"ashes","ashes");
        h.targets=List.of(new TargetQuery.Target("between",9),new TargetQuery.Target("edge",10),new TargetQuery.Target("outside",10.01));
        for(var t:h.targets)h.views.put(t.entity(),view(true,false));
        h.apply(0,FIRST,100);assertEquals(2,h.damage.size());assertEquals(120*.1*.1,h.amounts.getFirst(),1e-9);assertEquals(0,h.amounts.getLast(),1e-9);
        var scorched=h.state().buffs().instances().values().stream().filter(b->b.definition().id().equals(SCORCH)).toList();
        assertEquals(1,scorched.size());assertEquals("between",scorched.getFirst().key().holder());assertEquals(60,scorched.getFirst().count());
    }
}
