package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Source-backed charges, damage and Slow timing; explicitly synthetic flight and contact damage calibration. */
class WitheringBladeTest {
    static final String ABILITY="chorus_d2:withering_blade",SLOT="chorus_d2:melee",ENERGY="chorus_d2:withering_blade_energy";
    static CompiledEffects program(boolean calibrate)throws Exception {
        var data=json("withering_blade");
        if(calibrate)json("withering_blade_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e->data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value",e.getValue()));
        return CompiledEffects.link(List.of(DuranceTest.program().program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("withering_blade_energy")).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("withering_blade_damage_test_calibration")).getOrThrow()));
    }
    static AbilityUse.Request request(int cast){return new AbilityUse.Request("player",SLOT,"cast-"+cast,new EffectEvent("player","player",new BuffInstance.Origin("player","","",""),Set.of(),Map.of()));}
    static final class Harness {
        final CompiledEffects p;final EffectSession session;final List<ProjectileFlight.Launch> launches=new ArrayList<>();final List<DamageCommand> damage=new ArrayList<>();final List<StatusResult.Check> checks=new ArrayList<>();
        final Map<String,EntityQuery.View> entities=new HashMap<>();DamageReceipt.Outcome outcome=DamageReceipt.Outcome.APPLIED;boolean lethal,failDamage,denySlow;int cast;
        Harness(EffectState.Mode mode)throws Exception{this(mode,program(true));}
        Harness(EffectState.Mode mode,CompiledEffects program){
            p=program;entities.put("player",FreezeTest.view(true));entities.put("enemy",FreezeTest.view(false,"chorus_d2:elite"));entities.put("other",FreezeTest.view(false,"chorus_d2:elite"));
            session=new EffectSession(engine(p),EffectState.empty().withMode(mode),r->switch(r.command()){
                case PositionQuery q->new PositionQuery.Result(q,Optional.of(ProjectileDestinationTest.point(1,40,3)));
                case DirectionQuery q->new DirectionQuery.Result(q,Optional.of(new WorldDirection("world",0,1,0)));
                case ProjectileFlight.Launch l->{launches.add(l);yield new ProjectileFlight.Receipt(l,ProjectileFlight.Outcome.LAUNCHED,Optional.of("flight-"+launches.size()));}
                case EntityQuery q->new EntityQuery.Result(q,Optional.ofNullable(entities.get(q.target())));
                case DamageCommand d->{damage.add(d);if(failDamage)throw new IllegalStateException("unknown blade damage");yield DamageTallyTest.receipt("hit-"+damage.size(),outcome,outcome==DamageReceipt.Outcome.APPLIED?d.amount():0,lethal?"death":"");}
                case StatusResult.Check q->{checks.add(q);yield new StatusResult.Checked(q,denySlow?StatusResult.Decision.DENIED:StatusResult.Decision.ALLOWED);}
                default->throw new AssertionError(r.command());
            });select(true);
        }
        EffectState state(){return session.state().engine().domain();}
        void send(RuleEngine.Signal signal){session.start(state().buffs().timeMicros(),signal);assertTrue(session.state().engine().failure().isEmpty(),session.state().engine().failure().toString());}
        void select(boolean selected){send(new AbilityChange("player",state().abilities().getOrDefault("player",AbilityLoadout.EMPTY),selected?new AbilityLoadout(Map.of(SLOT,ABILITY)):AbilityLoadout.EMPTY).signal());}
        void cast(){send(request(++cast).signal());}
        double energy(){return state().resources().get(new ResourceState.Key("player",ENERGY)).value();}
        void contact(int flight,String target,int sequence,int walls,boolean terminal){send(launches.get(flight).finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,ProjectileDestinationTest.point(1,45,3),Optional.of(target),0,0,0,50_000,sequence,walls,sequence-walls,1,terminal)));}
        Optional<BuffInstance> buff(String id,String holder){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(id)&&b.key().holder().equals(holder)).findFirst();}
    }
    @Test void calibrationIsRequiredBeforePaymentAndComposedDefinitionsRoundtrip()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);var uncalibrated=program(false);assertThrows(IllegalArgumentException.class,()->uncalibrated.useAbility(h.state(),request(1)));assertEquals(2,h.energy());assertTrue(h.launches.isEmpty());
        assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
        assertThrows(RuntimeException.class,()->CompiledEffects.link(List.of(DuranceTest.program().program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("withering_blade")).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("withering_blade_energy")).getOrThrow())));
    }
    @Test void twoChargesRechargeSequentiallyAndSelectionCannotRefillTheAccount()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.cast();assertEquals(1,h.energy());h.cast();assertEquals(0,h.energy());
        assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,((AbilityUse.Receipt)h.p.useAbility(h.state(),request(3)).result()).outcome());h.cast();assertEquals(2,h.launches.size());
        h.select(false);h.select(true);assertEquals(0,h.energy());h.session.observe(72_600_000,List.of());assertEquals(.5,h.energy(),1e-12);
        h.session.observe(145_200_000,List.of());assertEquals(1,h.energy(),1e-12);h.cast();assertEquals(0,h.energy(),1e-12);assertEquals(3,h.launches.size());
        h.session.observe(435_600_000,List.of());assertEquals(2,h.energy(),1e-12);
    }
    @Test void actualTargetKindSelectsDamageStacksAndDurationWhileActivitySelectsTrackingRadius()throws Exception {
        for(var mode:EffectState.Mode.values())for(boolean guardian:List.of(false,true)){
            var h=new Harness(mode);h.entities.put("enemy",FreezeTest.view(guardian,"chorus_d2:elite"));h.cast();var launch=h.launches.getFirst();
            assertEquals(mode==EffectState.Mode.PVE?12:8,launch.parameters().tracking().orElseThrow().radius());assertEquals(3,launch.parameters().collision().totalContinuations().maximum().orElseThrow());
            h.contact(0,"enemy",1,0,false);assertEquals(guardian?72:296,h.damage.getFirst().amount());assertEquals(guardian?40:60,h.buff(SlowTest.S,"enemy").orElseThrow().count());assertEquals(guardian?1_500_000L:3_500_000L,h.checks.getLast().duration());
            assertEquals(Set.of("chorus:melee_damage","chorus_d2:stasis"),h.damage.getFirst().tags());assertEquals(Set.of("chorus:melee_kill"),h.damage.getFirst().killTags());assertEquals(ABILITY,h.damage.getFirst().source().ability());
        }
    }
    @Test void secondBladeFreezesACombatantWithTheFinalCastCreditButTwoGuardianHitsOnlyReachEighty()throws Exception {
        for(boolean guardian:List.of(false,true)){
            var h=new Harness(EffectState.Mode.PVE);h.entities.put("enemy",FreezeTest.view(guardian,"chorus_d2:elite"));h.cast();h.cast();h.contact(0,"enemy",1,0,true);h.contact(1,"enemy",1,0,true);
            if(guardian){assertEquals(80,h.buff(SlowTest.S,"enemy").orElseThrow().count());assertTrue(h.buff(SlowTest.F,"enemy").isEmpty());}
            else{assertTrue(h.buff(SlowTest.S,"enemy").isEmpty());var freeze=h.buff(SlowTest.F,"enemy").orElseThrow();assertEquals(h.damage.getLast().source(),freeze.origin());assertEquals(6_000_000L,freeze.deadline());}
            assertNotEquals(h.damage.getFirst().source(),h.damage.getLast().source());
        }
    }
    @Test void unequippedFlightsKeepCreditButDuranceIsQueriedAtEachApplication()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.cast();h.select(false);h.send(SourceChange.bind(DuranceTest.fragment("own","player")));h.contact(0,"enemy",1,0,false);
        assertEquals(7_000_000L,h.checks.getLast().duration());assertEquals(h.damage.getFirst().source(),h.buff(SlowTest.S,"enemy").orElseThrow().origin());
        h.send(SourceChange.remove("own"));h.send(SourceChange.bind(DuranceTest.fragment("recipient","other")));h.contact(0,"other",2,0,false);assertEquals(3_500_000L,h.checks.getLast().duration());
        assertTrue(h.state().sources().values().stream().noneMatch(s->s.bundle().equals("chorus_d2:slow_application")||s.bundle().equals("chorus_d2:withering_blade_energy_scaling")));
    }
    @Test void cancelledImmuneBlockedLethalAndDeniedHitsCannotInventSlow()throws Exception {
        for(var outcome:List.of(DamageReceipt.Outcome.CANCELLED,DamageReceipt.Outcome.IMMUNE,DamageReceipt.Outcome.BLOCKED,DamageReceipt.Outcome.APPLIED)){
            var h=new Harness(EffectState.Mode.PVE);h.outcome=outcome;h.lethal=outcome==DamageReceipt.Outcome.APPLIED;h.cast();h.contact(0,"enemy",1,0,true);assertTrue(h.checks.isEmpty());assertTrue(h.state().buffs().instances().isEmpty());
        }
        var denied=new Harness(EffectState.Mode.PVE);denied.denySlow=true;denied.cast();denied.contact(0,"enemy",1,0,true);assertEquals(1,denied.checks.size());assertTrue(denied.state().buffs().instances().isEmpty());
    }
    @Test void unknownDamageRetainsPaymentAndStopsBeforeSlowWithoutReplaying()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.cast();h.failDamage=true;assertThrows(IllegalStateException.class,()->h.contact(0,"enemy",1,0,true));assertEquals(1,h.energy());assertEquals(1,h.damage.size());assertTrue(h.checks.isEmpty());assertFalse(h.session.state().idle());assertThrows(IllegalStateException.class,()->h.session.observe(1_000_000,List.of()));assertEquals(1,h.damage.size());
    }
    @Test void explicitContactCalibrationReceivesWallAndEntityCountsWithoutChangingSlowStacks()throws Exception {
        var data=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,program(true).program()).getOrThrow().getAsJsonObject();
        for(var entry:data.getAsJsonArray("profiles")){var profile=entry.getAsJsonObject();if(profile.get("id").getAsString().equals("chorus_d2:withering_blade_contact_damage"))profile.add("steps",JsonParser.parseString("""
            [{"type":"chorus:apply","id":"contacts","operation":"multiply","group":{"name":"contacts","reduction":"max"}}]
            """));}
        data.getAsJsonArray("bundles").add(JsonParser.parseString("""
            {"id":"test:contact_calibration","modifiers":[{"id":"half_after_bounce","profile":"chorus_d2:withering_blade_contact_damage","stage":"contacts","group":"contacts","op":"multiply","stacking_key":"test:contacts","value":{"type":"chorus:constant","value":-0.5,"unit":"delta"},"if":{"type":"chorus:all","of":[{"type":"chorus:compare","left":{"type":"chorus:event_number","name":"sequence","unit":"count"},"op":"eq","right":{"type":"chorus:constant","value":3,"unit":"count"}},{"type":"chorus:compare","left":{"type":"chorus:event_number","name":"bounces","unit":"count"},"op":"eq","right":{"type":"chorus:constant","value":1,"unit":"count"}},{"type":"chorus:compare","left":{"type":"chorus:event_number","name":"entity_contacts","unit":"count"},"op":"eq","right":{"type":"chorus:constant","value":2,"unit":"count"}}]},"reference":"Synthetic one-half contact calibration, not Destiny 2 decay","confidence":"assumed"}]}
            """));var h=new Harness(EffectState.Mode.PVE,compile(data));h.send(SourceChange.bind(new EffectSource("calibration","test:contact_calibration","player",new BuffInstance.Origin("player","calibration","",""),Set.of())));h.cast();h.contact(0,"enemy",1,0,false);h.contact(0,"other",3,1,false);
        assertEquals(296,h.damage.getFirst().amount());assertEquals(148,h.damage.getLast().amount());assertEquals(60,h.buff(SlowTest.S,"other").orElseThrow().count());assertEquals(h.damage.getFirst().source(),h.damage.getLast().source());
    }
    @Test void meleeStatsAndDuranceAffectOwnRecoveryAndChunkGainWithoutDuplicatingThreadedSpikeDefinitions()throws Exception {
        var base=program(true);var fragments=new ArrayList<EffectProgram>();fragments.add(base.program());for(String name:List.of("threaded_spike","threaded_spike_energy","strand_defense","continuity"))fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(name)).getOrThrow());
        fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,JsonParser.parseString("""
            {"version":"compendium-2026-10-05","bundles":[{"id":"test:melee_points","parameters":{"points":"stat_point"},"modifiers":[{"id":"points","profile":"chorus_d2:melee_stat","stage":"bonuses","group":"bonuses","op":"add","stacking_key":"test:points","value":{"type":"chorus:source_parameter","name":"points"},"confidence":"assumed","reference":"Synthetic armor stat input"}]}]}
            """)).getOrThrow());var h=new Harness(EffectState.Mode.PVE,CompiledEffects.link(fragments));h.cast();h.cast();
        var origin=new BuffInstance.Origin("player","armor","","");h.send(SourceChange.bind(new EffectSource("armor","test:melee_points","player",origin,Set.of(),Map.of("points",new Measure(90,Unit.STAT_POINT)))));h.send(SourceChange.bind(DuranceTest.fragment("own","player")));
        var account=h.state().resources().get(new ResourceState.Key("player",ENERGY));assertEquals(2.75/145.2,h.p.resourceRate(h.state(),account).perSecond(),1e-12);
        var query=new EffectEvent("player","player",origin,Set.of(),Map.of());assertEquals(.072,h.p.calculate(h.state(),"player",query,"chorus_d2:withering_blade_gain",new Measure(.04,Unit.CHARGE),List.of()).output().value(),1e-12);
        h.session.observe(1_000_000,List.of());assertEquals(2.75/145.2,h.energy(),1e-12);h.send(SourceChange.remove("armor"));h.send(SourceChange.remove("own"));assertEquals(1/145.2,h.p.resourceRate(h.state(),h.state().resources().get(account.key())).perSecond(),1e-12);
    }
}
