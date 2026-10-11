package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.target.EntityQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class VoidOvershieldTest {
    static final String BUFF="chorus_d2:void_overshield";
    static EffectSource grantSource(String name,String owner,double amount,double duration){return new EffectSource(name,"test:void_granter",owner,new BuffInstance.Origin(owner,name,"",""),Set.of(),Map.of("amount",new Measure(amount,Unit.DAMAGE),"duration",new Measure(duration,Unit.SECOND)));}
    static final class Harness {
        final CompiledEffects program;final EffectSession session;final Map<String,EntityQuery.View> entities=new HashMap<>();int observations;
        Harness()throws Exception{program=link("void_overshield","void_overshield_inputs");entities.put("recipient",FreezeTest.view(true));entities.put("other",FreezeTest.view(true));session=new EffectSession(engine(program),EffectState.empty(),r->{if(r.command() instanceof EntityQuery q){observations++;return new EntityQuery.Result(q,Optional.ofNullable(entities.get(q.target())));}throw new AssertionError(r.command());});}
        EffectState state(){return session.state().engine().domain();}
        Optional<BuffInstance> shield(String who){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(BUFF)&&b.key().holder().equals(who)).findFirst();}
        void grant(String name,String owner,String recipient,double amount,double duration){var source=grantSource(name,owner,amount,duration);session.start(state().buffs().timeMicros(),SourceChange.bind(source));session.start(state().buffs().timeMicros(),new RuleEngine.Signal("test:grant_void",new EffectEvent(owner,recipient,source.origin(),Set.of(),Map.of(),Map.of(),Map.of("source_instance",name,"bundle",source.bundle()))));}
        void extension(String who){session.start(state().buffs().timeMicros(),SourceChange.bind(new EffectSource("extension/"+who,"test:void_duration",who,new BuffInstance.Origin(who,"extension/"+who,"",""),Set.of())));}
    }
    @Test void independentGrantorsShareOneCappedPoolWhileRecipientsRemainIndependent()throws Exception{
        var h=new Harness();h.grant("a","first","recipient",15,10);var first=h.shield("recipient").orElseThrow();
        h.grant("b","second","recipient",15,10);h.grant("c","third","recipient",100,10);var after=h.shield("recipient").orElseThrow();
        assertEquals(45,after.components().numbers().get("capacity"));assertEquals(first.generation(),after.generation());assertEquals(first.origin(),after.origin());assertEquals(1,h.state().buffs().instances().size());
        h.grant("d","first","other",15,10);assertEquals(15,h.shield("other").orElseThrow().components().numbers().get("capacity"));assertEquals(45,h.shield("recipient").orElseThrow().components().numbers().get("capacity"));
    }
    @Test void combatantReductionOnlySpendsShieldBudgetAndNeverReducesHealthOverflow()throws Exception{
        var h=new Harness();h.grant("a","giver","recipient",45,10);
        for(var mode:EffectState.Mode.values())for(String kind:List.of("chorus:combatant","chorus:guardian","test:environment")){
            var origin=new BuffInstance.Origin("attacker","attack","","",Set.of(kind));var damage=new DamageCommand("recipient",origin,170,"minecraft:generic",Set.of(),Set.of(),false);
            var plan=h.program.shields(h.state().withMode(mode),damage,170);
            assertEquals(kind.equals("chorus:combatant")?20:125,plan.budget().toVanilla(),1e-10);assertEquals(45,plan.budget().shieldLoss(),1e-10);
        }
    }
    @Test void recipientDurationModifiersDoNotReadTheGiversBuildAndShorterGrantsDoNotShortenTheWindow()throws Exception{
        var h=new Harness();h.extension("giver");h.grant("a","giver","recipient",15,10);assertEquals(10_000_000L,h.shield("recipient").orElseThrow().deadline());
        h.extension("recipient");h.session.observe(1_000_000,List.of());h.grant("b","giver","recipient",15,10);assertEquals(16_000_000L,h.shield("recipient").orElseThrow().deadline());
        h.session.observe(2_000_000,List.of());h.grant("c","giver","recipient",15,2);assertEquals(16_000_000L,h.shield("recipient").orElseThrow().deadline());
        h.session.start(2_000_000,SourceChange.remove("extension/recipient"));h.session.start(2_000_000,SourceChange.remove("a"));h.session.start(2_000_000,SourceChange.remove("b"));h.session.start(2_000_000,SourceChange.remove("c"));
        h.session.observe(15_999_999,List.of());assertTrue(h.shield("recipient").isPresent());h.session.observe(16_000_000,List.of());assertTrue(h.shield("recipient").isEmpty());
    }
    @Test void zeroMissingAndDeadRecipientsCannotCreateEmptyDefensiveBuffs()throws Exception{
        var h=new Harness();h.grant("zero","giver","recipient",0,10);assertEquals(0,h.observations);assertTrue(h.shield("recipient").isEmpty());
        h.grant("missing","giver","absent",15,10);assertTrue(h.shield("absent").isEmpty());
        h.entities.put("recipient",new EntityQuery.View(false,true,0,100,0,Set.of(),Set.of()));h.grant("dead","giver","recipient",15,10);assertTrue(h.shield("recipient").isEmpty());
    }
}
