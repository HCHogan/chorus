package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real producer damage, independent of which other Solar ability happens to be selected. */
class SolarProducerAssemblyTest {
    static final String D="chorus_d2:";
    static FireboltTest.Harness grenade(String kind)throws Exception {
        FireboltTest.Harness h=switch(kind){case "firebolt"->new FireboltTest.Harness();case "fusion_grenade"->new FusionGrenadeTest.Harness();default->new SolarGrenadeTest.Harness();};
        h.positions.keySet().removeIf(x->!x.equals("player"));h.put("enemy",0);return h;
    }
    static void detonate(FireboltTest.Harness h,String kind) {
        h.land();
        if(kind.equals("firebolt")){h.until(h.now()+1_000_000);h.arrive(h.launches.size()-1);}
        if(kind.equals("fusion_grenade"))h.until(h.now()+600_000);
    }
    static List<Integer> dots(FireboltTest.Harness h) {
        var indices=new ArrayList<Integer>();for(int i=0;i<h.hits.size();i++)if(h.hits.get(i).tags().contains(D+"scorch_damage"))indices.add(i);return indices;
    }
    @Test void eachGrenadeDealsActualScorchAfterItsSelectionIsRemovedBeforeImpact()throws Exception {
        for(String kind:List.of("firebolt","fusion_grenade","solar_grenade")) {
            var h=grenade(kind);h.cast();h.choose(null);detonate(h,kind);double before=h.hp.get("enemy");h.until(h.now()+500_000);
            var indices=dots(h);assertEquals(1,indices.size(),kind);int index=indices.getFirst();double amount=h.amounts.get(index);
            assertTrue(amount>0,kind+" emitted a zero-damage Scorch command");assertEquals(amount,before-h.hp.get("enemy"),1e-10,kind);
            var dot=h.hits.get(index);assertEquals(D+kind,dot.source().ability());assertEquals(Set.of("chorus:grenade_kill","chorus:ability_kill"),dot.killTags());
        }
    }
    @Test void ownerPayloadSurvivesCheckpointAndDuplicateSystemSourcesDoNotDoubleIt()throws Exception {
        for(String kind:List.of("firebolt","fusion_grenade","solar_grenade")) {
            var amounts=new ArrayList<Double>();
            for(boolean duplicate:List.of(false,true)) {
                var h=grenade(kind);
                if(duplicate)h.send(SourceChange.bind(new EffectSource("extra-solar",D+"solar_scaling","player",new BuffInstance.Origin("player","extra-solar","",""),Set.of())));
                h.cast();h.choose(null);h.restore();detonate(h,kind);h.restore();h.until(h.now()+500_000);amounts.add(h.amounts.get(dots(h).getFirst()));
            }
            assertTrue(amounts.getFirst()>0,kind);assertEquals(amounts.getFirst(),amounts.getLast(),1e-12,kind);
        }
    }
    @Test void anotherGrenadeAddsStacksWithoutReplacingTheFirstDamageAndKillCredit()throws Exception {
        var h=grenade("fusion_grenade");h.choose(D+"firebolt");h.cast();detonate(h,"firebolt");
        h.choose(D+"fusion_grenade");h.full();h.cast();h.impact(h.launches.size()-1,ProjectileFlight.End.BLOCK);h.until(h.now()+600_000);
        assertEquals(60,h.buff("scorch","enemy").orElseThrow().quantity());h.choose(null);h.until(h.now()+900_000);
        assertTrue(dots(h).size()>=2,"first tick at 0.5 seconds and next at 1.43 seconds after initial application");for(int index:dots(h)){assertTrue(h.amounts.get(index)>0);assertEquals(D+"firebolt",h.hits.get(index).source().ability());}
    }
    @Test void phoenixLandingAfterClassDeselectionStillCapturesPositiveClassScorch()throws Exception {
        var h=new PhoenixDiveTest.Harness();h.input("heat");h.use("class",false);var before=h.state().abilities().get("player");var slots=new HashMap<>(before.slots());slots.remove(D+"class");h.send(new AbilityChange("player",before,new AbilityLoadout(slots)).signal());h.land();h.until(500_000);
        var dot=h.hits.stream().filter(c->c.tags().contains(D+"scorch_damage")&&c.target().equals("enemy")).findFirst().orElseThrow();
        assertTrue(h.p.outgoing(h.state(),dot,dot.amount()).orElseThrow().output().value()>0,"deselection removed the shared payload source");
        assertEquals(D+"phoenix_dive",dot.source().ability());assertTrue(dot.killTags().contains("chorus:class_kill"));
    }
}
