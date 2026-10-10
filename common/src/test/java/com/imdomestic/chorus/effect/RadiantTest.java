package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class RadiantTest {
    static final String RAD="chorus_d2:radiant", PROFILE="chorus_d2:empowering_damage", SOLACE="chorus_d2:ember_of_solace";
    static CompiledEffects program()throws Exception{return link("radiant","empowering_damage","radiant_inputs","solar_effect_duration","ember_of_solace");}
    static class Harness {
        final CompiledEffects program;final EffectSession session;
        StatusResult.Decision decision=StatusResult.Decision.ALLOWED;
        Harness(EffectState.Mode mode)throws Exception{this(program(),mode);}
        Harness(CompiledEffects program,EffectState.Mode mode){
            this.program=program;session=new EffectSession(engine(program),EffectState.empty().withMode(mode),this::execute);
            bind("player","test:radiant_inputs");bind("ally","test:radiant_inputs");
        }
        RuleEngine.ActionResult execute(RuleEngine.WorldRequest r){var q=(StatusResult.Check)r.command();return new StatusResult.Checked(q,decision);}
        static EffectSource source(String owner,String bundle){return new EffectSource(owner+"/"+bundle,bundle,owner,new BuffInstance.Origin(owner,bundle,"",""),Set.of());}
        EffectState state(){return session.state().engine().domain();}long now(){return state().buffs().timeMicros();}
        void healthy(){assertTrue(session.state().idle(),()->session.state().engine().failure().toString());}
        void bind(String owner,String bundle){session.start(now(),SourceChange.bind(source(owner,bundle)));healthy();}
        void remove(String owner,String bundle){session.start(now(),SourceChange.remove(source(owner,bundle).instance()));healthy();}
        void grant(String owner,String target,String kind,double duration){session.start(now(),new RuleEngine.Signal("test:"+kind,new EffectEvent(owner,target,source(owner,"test:radiant_inputs").origin(),Set.of(),Map.of("duration",new Measure(duration,Unit.SECOND)))));healthy();}
        void until(long time){session.observe(time,List.of());healthy();}
        Optional<BuffInstance> buff(String owner){return state().buffs().instances().values().stream().filter(b->b.key().holder().equals(owner)&&b.definition().id().equals(RAD)).findFirst();}
        DamageCommand command(Set<String> tags,ImpactData impact){return new DamageCommand("target",new BuffInstance.Origin("player","shot","rifle","",Set.of("chorus_d2:solar")),10,"minecraft:generic",tags,Set.of(),false,Optional.of(PROFILE),Optional.empty(),impact);}
        double damage(Set<String> tags,int champion){return program.outgoing(state(),command(tags,impact(champion)),10).orElseThrow().output().value();}
    }
    static ImpactData impact(double champion){return new ImpactData(Map.of("radiant_champion",new Measure(champion,Unit.COUNT)));}
    @Test void bothModesAndExplicitWeaponOrGoldenGunEligibilityUseChampionValueAtImpact()throws Exception{
        for(var mode:EffectState.Mode.values()){
            var h=new Harness(mode);h.grant("player","player","radiant",10);
            for(String tag:List.of("chorus:weapon_damage","chorus_d2:golden_gun_damage")){
                assertEquals(mode==EffectState.Mode.PVE?12:11,h.damage(Set.of(tag),0),1e-12);
                assertEquals(mode==EffectState.Mode.PVE?13:11,h.damage(Set.of(tag),1),1e-12);
            }
            assertEquals(10,h.damage(Set.of("chorus:grenade_damage","chorus:ability_damage"),1),1e-12);
        }
    }
    @Test void wellPriorityOverridesThirtyPercentWithoutAddingButIndependentPerkStillMultiplies()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);var tags=Set.of("chorus:weapon_damage");h.grant("player","player","radiant",10);h.bind("player","test:independent_perk");
        assertEquals(14.3,h.damage(tags,1),1e-12);h.grant("player","player","well",1);assertEquals(13.75,h.damage(tags,1),1e-12);
        h.until(1_000_000);assertEquals(14.3,h.damage(tags,1),1e-12);assertEquals(10_000_000,h.buff("player").orElseThrow().deadline());
    }
    @Test void firingSamplesBuffAvailabilityWhileEachLaterImpactKeepsItsOwnChampionClassification()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);h.grant("player","player","radiant",.05);
        var saved=h.program.captureDamage(h.state(),h.command(Set.of("chorus:weapon_damage"),ImpactData.EMPTY));
        h.until(100_000);h.remove("player","test:radiant_inputs");assertTrue(h.buff("player").isEmpty());
        assertEquals(12,h.program.outgoing(h.state(),saved.command("ordinary",impact(0)),10).orElseThrow().output().value(),1e-12);
        assertEquals(13,h.program.outgoing(h.state(),saved.command("champion",impact(1)),10).orElseThrow().output().value(),1e-12);
        assertEquals(10,h.damage(Set.of("chorus:weapon_damage"),1),1e-12);
    }
    @Test void recipientSolaceAndReapplicationPreserveHistoricDurationWithoutChangingAnotherHolder()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);h.bind("player",SOLACE);h.grant("player","ally","radiant",10);
        assertEquals(10_000_000,h.buff("ally").orElseThrow().deadline());h.bind("ally",SOLACE);h.grant("player","ally","radiant",10);
        assertEquals(15_000_000,h.buff("ally").orElseThrow().deadline());h.until(1_000_000);h.remove("ally",SOLACE);h.grant("ally","ally","radiant",2);
        assertEquals(16_000_000,h.buff("ally").orElseThrow().deadline());assertTrue(h.buff("player").isEmpty());
        h.decision=StatusResult.Decision.DENIED;h.grant("player","player","radiant",10);assertTrue(h.buff("player").isEmpty());
    }
    @Test void missingAndInvalidClassificationNeverPretendTheVictimIsOrdinaryAndDefinitionsRoundTrip()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);h.grant("player","player","radiant",10);
        assertThrows(IllegalArgumentException.class,()->h.program.outgoing(h.state(),h.command(Set.of("chorus:weapon_damage"),ImpactData.EMPTY),10));
        for(double value:List.of(-1d,.5,2d))assertThrows(IllegalArgumentException.class,()->h.program.outgoing(h.state(),h.command(Set.of("chorus:weapon_damage"),impact(value)),10));
        var wrong=new ImpactData(Map.of("radiant_champion",new Measure(1,Unit.SECOND)));
        assertThrows(IllegalArgumentException.class,()->h.program.outgoing(h.state(),h.command(Set.of("chorus:weapon_damage"),wrong),10));
        var encoded=EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,h.program).getOrThrow();assertEquals(h.program.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,encoded).getOrThrow().program());
    }
}
