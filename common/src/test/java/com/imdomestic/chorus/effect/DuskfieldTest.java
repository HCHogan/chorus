package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
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

/** Source-backed field cadence; initial blast, first pulse and physical flight are explicitly calibrated. */
class DuskfieldTest {
    static final String ABILITY="chorus_d2:duskfield",FIELD="chorus_d2:duskfield_field",SLOT="chorus_d2:grenade",ENERGY="chorus_d2:duskfield_energy";
    static CompiledEffects program(boolean calibrated)throws Exception{
        var data=json("duskfield");if(calibrated)json("duskfield_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e->data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value",e.getValue()));
        var fragments=new ArrayList<EffectProgram>();fragments.add(DuranceTest.program().program());fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());
        for(String n:List.of("duskfield_energy","duskfield_damage_test_calibration","duskfield_inputs"))fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(n)).getOrThrow());return CompiledEffects.link(fragments);
    }
    static WorldPosition point(double x){return new WorldPosition("world",x,40,0);}
    static AbilityUse.Request request(String player,int cast){return new AbilityUse.Request(player,SLOT,"dusk-"+cast,new EffectEvent(player,player,new BuffInstance.Origin(player,"","",""),Set.of(),Map.of()));}
    static class Harness{
        final CompiledEffects p;final EffectSession session;final List<ProjectileFlight.Launch> launches=new ArrayList<>();final List<DamageCommand> damage=new ArrayList<>();final List<Long> hitTimes=new ArrayList<>();final List<StatusResult.Check> checks=new ArrayList<>();final List<TargetQuery> queries=new ArrayList<>();
        final Map<String,WorldPosition> positions=new HashMap<>();final Map<String,EntityQuery.View> entities=new HashMap<>();final Set<String> allies=new HashSet<>();boolean denySlow,failDamage;DamageReceipt.Outcome outcome=DamageReceipt.Outcome.APPLIED;int casts;
        Harness(EffectState.Mode mode)throws Exception{
            p=program(true);positions.put("player",point(-10));positions.put("other",point(100));positions.put("enemy",point(0));entities.put("player",FreezeTest.view(true));entities.put("other",FreezeTest.view(true));entities.put("enemy",FreezeTest.view(false,"chorus_d2:elite"));
            session=new EffectSession(engine(p),EffectState.empty().withMode(mode),r->switch(r.command()){
                case PositionQuery q->new PositionQuery.Result(q,Optional.ofNullable(positions.get(q.target())));
                case DirectionQuery q->new DirectionQuery.Result(q,Optional.of(new WorldDirection("world",1,0,0)));
                case ProjectileFlight.Launch l->{launches.add(l);yield new ProjectileFlight.Receipt(l,ProjectileFlight.Outcome.LAUNCHED,Optional.of("flight-"+launches.size()));}
                case EntityQuery q->new EntityQuery.Result(q,Optional.ofNullable(entities.get(q.target())));
                case TargetQuery q->{queries.add(q);var center=((TargetQuery.PositionCenter)q.center()).position().orElseThrow();var targets=new ArrayList<TargetQuery.Target>();boolean relative=positions.containsKey(q.relativeTo());
                    if(relative)for(var entry:positions.entrySet()){double d=Math.abs(entry.getValue().x()-center.x());if(d<=q.radius()&&!q.exclude().contains(entry.getKey())&&!allies.contains(entry.getKey())&&entities.get(entry.getKey()).alive())targets.add(new TargetQuery.Target(entry.getKey(),d));}targets.sort(q.comparator());yield new TargetQuery.Result(q,relative?TargetQuery.Outcome.AVAILABLE:TargetQuery.Outcome.MISSING_RELATIVE,targets);}
                case DamageCommand d->{damage.add(d);hitTimes.add(state().buffs().timeMicros());if(failDamage)throw new IllegalStateException("unknown duskfield hit");yield DamageTallyTest.receipt("hit-"+damage.size(),outcome,outcome==DamageReceipt.Outcome.APPLIED?d.amount():0,"");}
                case StatusResult.Check q->{checks.add(q);yield new StatusResult.Checked(q,denySlow?StatusResult.Decision.DENIED:StatusResult.Decision.ALLOWED);}
                default->throw new AssertionError(r.command());
            });select("player",true);select("other",true);
        }
        EffectState state(){return session.state().engine().domain();}
        void send(RuleEngine.Signal signal){session.start(state().buffs().timeMicros(),signal);assertTrue(session.state().engine().failure().isEmpty(),session.state().engine().failure().toString());}
        void until(long time){session.observe(time,List.of());assertTrue(session.state().idle());}
        void select(String holder,boolean selected){send(new AbilityChange(holder,state().abilities().getOrDefault(holder,AbilityLoadout.EMPTY),selected?new AbilityLoadout(Map.of(SLOT,ABILITY)):AbilityLoadout.EMPTY).signal());}
        void cast(String holder){send(request(holder,++casts).signal());}
        void land(int flight,WorldPosition place,ProjectileFlight.End end){send(launches.get(flight).finish(new ProjectileFlight.Impact(end,place,end==ProjectileFlight.End.ENTITY?Optional.of("enemy"):Optional.empty(),0,end==ProjectileFlight.End.BLOCK?1:0,0,50_000)));}
        void land(){land(0,point(0),ProjectileFlight.End.BLOCK);}
        List<BuffInstance> fields(){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(FIELD)).toList();}
        Optional<BuffInstance> status(String id,String target){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(id)&&b.key().holder().equals(target)).findFirst();}
        double energy(){return state().resources().get(new ResourceState.Key("player",ENERGY)).value();}
        void dismiss(){var s=new EffectSource("dismiss","test:duskfield_control","player",new BuffInstance.Origin("player","dismiss","",""),Set.of());send(SourceChange.bind(s));send(new RuleEngine.Signal("test:dismiss_duskfield",new EffectEvent("player","player",s.origin(),Set.of(),Map.of(),Map.of(),Map.of("source_instance",s.instance(),"bundle",s.bundle()))));}
    }
    @Test void missingCalibrationFailsBeforePaymentAndKnownEnergyUsesCurrentGrenadeStats()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);assertThrows(IllegalArgumentException.class,()->program(false).useAbility(h.state(),request("player",1)));assertEquals(1,h.energy());h.cast("player");assertEquals(0,h.energy());
        var account=h.state().resources().get(new ResourceState.Key("player",ENERGY));assertEquals(1/131.7,h.p.resourceRate(h.state(),account).perSecond(),1e-12);
        var origin=new BuffInstance.Origin("player","armor","","");h.send(SourceChange.bind(new EffectSource("armor","test:duskfield_stats","player",origin,Set.of(),Map.of("points",new Measure(100,Unit.STAT_POINT)))));assertEquals(2.75/131.7,h.p.resourceRate(h.state(),account).perSecond(),1e-12);
        assertEquals(.07875,h.p.calculate(h.state(),"player",new EffectEvent("player","player",origin,Set.of(),Map.of()),"chorus_d2:duskfield_gain",new Measure(.04,Unit.CHARGE),List.of()).output().value(),1e-12);
        h.select("player",false);h.select("player",true);assertEquals(0,h.energy());assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
    }
    @Test void impactAndPeriodicValuesAreSeparateAndExpiryHasNoExtraEndpointPulse()throws Exception{
        for(var mode:EffectState.Mode.values()){
            var h=new Harness(mode);boolean guardian=mode==EffectState.Mode.PVP;h.entities.put("enemy",FreezeTest.view(guardian,"chorus_d2:elite"));h.cast("player");h.land();long period=guardian?300_000:350_000;
            assertEquals(20,h.damage.getFirst().amount());assertEquals(guardian?10:20,h.status(SlowTest.S,"enemy").orElseThrow().count());assertEquals(7_000_000L,h.fields().getFirst().deadline());
            h.until(period-1);assertEquals(1,h.damage.size());h.until(period);assertEquals(1,h.damage.getLast().amount());assertEquals("chorus_d2:ability_dot",h.damage.getLast().damageType());assertEquals(guardian?15:30,h.status(SlowTest.S,"enemy").orElseThrow().count());
            h.until(7_000_000);assertEquals(guardian?24:20,h.damage.size());assertTrue(h.fields().isEmpty());assertTrue(h.hitTimes.stream().allMatch(t->t<7_000_000));h.until(10_000_000);assertEquals(guardian?24:20,h.damage.size());
            assertTrue(h.damage.stream().allMatch(d->d.source().ability().equals(ABILITY)&&d.killTags().equals(Set.of("chorus:grenade_kill"))));
        }
    }
    @Test void fieldIsFixedAndEachPulseReadsCurrentMembersAndTeamInsteadOfFollowingTheCaster()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);h.cast("player");h.land();h.positions.put("player",point(100));h.positions.put("enemy",point(4.01));h.positions.put("late",point(4));h.entities.put("late",FreezeTest.view(false,"chorus_d2:elite"));h.until(350_000);
        assertEquals(List.of("enemy","late"),h.damage.stream().map(DamageCommand::target).toList());assertEquals(10,h.status(SlowTest.S,"late").orElseThrow().count());assertEquals(20,h.status(SlowTest.S,"enemy").orElseThrow().count());
        h.allies.add("late");h.until(700_000);assertEquals(2,h.damage.size());assertTrue(h.queries.stream().allMatch(q->q.center().equals(new TargetQuery.PositionCenter(point(0)))));assertEquals(4,h.queries.getLast().radius());
    }
    @Test void duranceIsSampledAtFieldCreationAndEachSlowApplicationIndependently()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);h.cast("player");h.select("player",false);h.send(SourceChange.bind(DuranceTest.fragment("own","player")));h.land();assertEquals(9_000_000L,h.fields().getFirst().deadline());assertEquals(4_000_000L,h.checks.getLast().duration());
        h.send(SourceChange.remove("own"));h.until(350_000);assertEquals(2_000_000L,h.checks.getLast().duration());assertEquals(4_000_000L,h.status(SlowTest.S,"enemy").orElseThrow().deadline());assertEquals(9_000_000L,h.fields().getFirst().deadline());
        h.positions.remove("enemy");h.until(8_999_999);assertEquals(1,h.fields().size());h.until(9_000_000);assertTrue(h.fields().isEmpty());
    }
    @Test void sharedSlowReachesFreezeFromTheFieldWithoutASecondCastAndDuranceDoesNotExtendFreeze()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);h.cast("player");h.land();h.until(2_799_999);assertEquals(90,h.status(SlowTest.S,"enemy").orElseThrow().count());assertTrue(h.status(SlowTest.F,"enemy").isEmpty());h.until(2_800_000);assertTrue(h.status(SlowTest.S,"enemy").isEmpty());assertEquals(8_800_000L,h.status(SlowTest.F,"enemy").orElseThrow().deadline());assertEquals(h.damage.getFirst().source(),h.status(SlowTest.F,"enemy").orElseThrow().origin());
    }
    @Test void independentCastsKeepAnchorsCreditAndLifetimesAndExplicitDismissalLeavesExistingSlow()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);h.cast("player");h.land();h.until(100_000);h.cast("other");h.land(1,point(1),ProjectileFlight.End.BLOCK);assertEquals(2,h.fields().size());h.until(350_000);assertEquals(3,h.damage.size());h.dismiss();assertEquals(1,h.fields().size());assertEquals("other",h.fields().getFirst().origin().owner());assertTrue(h.status(SlowTest.S,"enemy").isPresent());h.until(450_000);assertEquals(4,h.damage.size());assertEquals("other",h.damage.getLast().source().owner());h.until(7_100_000);assertTrue(h.fields().isEmpty());
    }
    @Test void cancelledInitialDamageOrDeniedSlowDoesNotPreventTheIndependentFieldButMissingOwnerEndsIt()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);h.outcome=DamageReceipt.Outcome.CANCELLED;h.cast("player");h.land();assertEquals(1,h.fields().size());assertTrue(h.checks.isEmpty());h.outcome=DamageReceipt.Outcome.APPLIED;h.denySlow=true;h.until(350_000);assertEquals(1,h.checks.size());assertTrue(h.status(SlowTest.S,"enemy").isEmpty());h.positions.remove("player");h.until(700_000);assertTrue(h.fields().isEmpty());assertTrue(h.state().timers().isEmpty());
        for(var end:List.of(ProjectileFlight.End.EXPIRED,ProjectileFlight.End.UNLOADED)){var t=new Harness(EffectState.Mode.PVE);t.cast("player");t.land(0,point(0),end);assertTrue(t.fields().isEmpty()&&t.damage.isEmpty());assertEquals(0,t.energy());}
    }
    @Test void unknownPulseRetainsSpentChargeExistingFieldAndPriorDamageWithoutReplay()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);h.cast("player");h.land();h.failDamage=true;assertThrows(IllegalStateException.class,()->h.until(350_000));assertEquals(2,h.damage.size());assertEquals(1,h.fields().size());assertEquals(1,h.checks.size());assertFalse(h.session.state().idle());assertEquals(.35/131.7,h.energy(),1e-12);assertThrows(IllegalStateException.class,()->h.until(700_000));assertEquals(2,h.damage.size());
    }
}
