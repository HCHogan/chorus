package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SuspendTest {
    static final String SUSPEND="chorus_d2:suspend";
    static EffectSource source(String id,String holder,double height,double step){return source(id,holder,height,step,2);}
    static EffectSource source(String id,String holder,double height,double step,double hover){return new EffectSource(id,"chorus_d2:suspend_application",holder,new BuffInstance.Origin(holder,id,"weapon-"+holder,"ability-"+holder,Set.of("test:caster")),Set.of(),Map.of("lift_height",new Measure(height,Unit.METER),"lift_step",new Measure(step,Unit.METER),"hover_speed",new Measure(hover,Unit.METER_PER_SECOND)));}
    static EntityQuery.View view(boolean player,String...tags){return new EntityQuery.View(true,player,1000,1000,0,Set.of(tags),Set.of());}
    static class Harness {
        final CompiledEffects p;final EffectSession session;final EffectSource source=source("apply","caster",1.05,.25);
        final List<StatusResult.Check> checks=new ArrayList<>();final List<Displacement.Receipt> moves=new ArrayList<>();final List<DamageCommand> damage=new ArrayList<>();
        EntityQuery.View target;WorldPosition position=new WorldPosition("world",0,0,0);double ceiling=100;StatusResult.Decision decision=StatusResult.Decision.ALLOWED;boolean failDamage;
        Harness(EntityQuery.View target)throws Exception{this(target,EffectState.Mode.PVE);}
        Harness(EntityQuery.View target,EffectState.Mode mode)throws Exception{
            this.target=target;p=link("suspend","continuity","combat_damage");
            session=new EffectSession(engine(p),EffectState.empty().withMode(mode).withSource(source),r->switch(r.command()){
                case EntityQuery q->new EntityQuery.Result(q,Optional.ofNullable(this.target));
                case PositionQuery q->new PositionQuery.Result(q,Optional.of(position));
                case StatusResult.Check q->{checks.add(q);yield new StatusResult.Checked(q,decision);}
                case Displacement.Command command->{var request=command.requested().orElseThrow();var resolved=new Displacement.Offset(request.x(),Math.min(request.y(),ceiling-position.y()),request.z());var after=new WorldPosition(position.dimension(),position.x()+resolved.x(),position.y()+resolved.y(),position.z()+resolved.z());var change=new Displacement.Change(position,after,resolved);position=after;var receipt=new Displacement.Receipt(command,change.changed()?Displacement.Outcome.APPLIED:Displacement.Outcome.UNCHANGED,Optional.of(change));moves.add(receipt);yield receipt;}
                case DamageCommand command->{damage.add(command);if(failDamage)throw new IllegalStateException("unknown boss snap outcome");yield new DamageReceipt(r.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,command.amount(),Optional.empty(),false);}
                default->throw new AssertionError(r.command());
            });
        }
        EffectState state(){return session.state().engine().domain();}
        void send(RuleEngine.Signal signal){session.start(state().buffs().timeMicros(),signal);}
        void event(EffectSource source,String name){send(new RuleEngine.Signal("chorus_d2:"+name,new EffectEvent(source.holder(),"target",source.origin(),Set.of(),Map.of(),Map.of(),Map.of("source_instance",source.instance(),"bundle",source.bundle()))));}
        void apply(){event(source,"apply_suspend");}
        void clear(){event(source,"clear_suspend");}
        void until(long time){session.observe(time,List.of());}
        void fragment(String id,String holder){send(SourceChange.bind(new EffectSource(id,"chorus_d2:continuity",holder,new BuffInstance.Origin(holder,id,"",""),Set.of())));}
        Optional<BuffInstance> status(){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(SUSPEND)).findFirst();}
        com.imdomestic.chorus.effect.motion.HorizontalSpeedLimit.Decision speed(){return p.horizontalSpeedLimit(state(),new EffectEvent("target","",new BuffInstance.Origin("target","native","",""),Set.of(),Map.of()));}
        boolean allowed(ActionGate.Kind kind){return p.checkAction(state(),kind,ActionGate.Phase.START,new EffectEvent("target","",new BuffInstance.Origin("target","native","",""),Set.of(),Map.of())).allowed();}
    }
    @Test void actualRecipientClassificationSelectsDurationsIndependentlyOfGlobalPvpMode()throws Exception{
        for(var mode:EffectState.Mode.values())for(String kind:List.of("rank_and_file","elite","miniboss","boss","guardian")){
            var h=new Harness(view(kind.equals("guardian"),"chorus_d2:"+kind),mode);h.fragment("victim","target");h.fragment("ally","ally");h.apply();
            long base=switch(kind){case "miniboss"->3;case "boss"->1;case "guardian"->2;default->6;};assertEquals(base*1_000_000,h.checks.getLast().duration());
            h.fragment("own","caster");h.fragment("duplicate","caster");h.apply();long extra=switch(kind){case "boss"->0;case "guardian","miniboss"->1;default->2;};assertEquals((base+extra)*1_000_000,h.checks.getLast().duration());assertEquals(h.source.origin(),h.status().orElseThrow().origin());
        }
    }
    @Test void combatantsLoseNewActionsAndMotionWhileGuardiansKeepHorizontalMovementAndWeapons()throws Exception{
        for(String kind:List.of("rank_and_file","miniboss","boss","guardian")){
            var h=new Harness(view(kind.equals("guardian"),"chorus_d2:"+kind));h.apply();assertEquals(kind.equals("guardian")?OptionalDouble.of(2):OptionalDouble.empty(),h.speed().maximum());
            for(var gate:ActionGate.Kind.values()){
                boolean denies= switch(kind){case "boss"->false;case "guardian"->gate==ActionGate.Kind.VERTICAL_MOTION||gate==ActionGate.Kind.JUMP;default->Set.of(ActionGate.Kind.ABILITY_USE,ActionGate.Kind.WEAPON_FIRE,ActionGate.Kind.RANGED_ATTACK,ActionGate.Kind.MELEE_ATTACK,ActionGate.Kind.HORIZONTAL_MOTION,ActionGate.Kind.VERTICAL_MOTION,ActionGate.Kind.JUMP).contains(gate);};
                assertEquals(!denies,h.allowed(gate),kind+" "+gate);
            }
        }
    }
    @Test void guardianCeilingIsAnExplicitFirstApplicationSnapshotAndEndsWithItsBuff()throws Exception{
        var h=new Harness(view(true));h.apply();assertEquals(2,h.speed().maximum().orElseThrow());assertEquals(h.source.origin(),h.speed().contributions().getFirst().origin());
        h.until(500_000);var other=source("other","second-caster",1.05,.25,.5);h.send(SourceChange.bind(other));h.event(other,"apply_suspend");h.send(SourceChange.remove(h.source.instance()));assertEquals(2,h.speed().maximum().orElseThrow());
        h.until(2_499_999);assertEquals(2,h.speed().maximum().orElseThrow());h.until(2_500_000);assertTrue(h.speed().maximum().isEmpty());
        h.event(other,"apply_suspend");assertEquals(.5,h.speed().maximum().orElseThrow());h.event(other,"clear_suspend");assertTrue(h.speed().maximum().isEmpty());
        for(double invalid:new double[]{0,-1}){var bad=new Harness(view(true));var source=source("bad","caster",1.05,.25,invalid);bad.send(SourceChange.bind(source));bad.event(source,"apply_suspend");assertTrue(bad.status().isEmpty());assertTrue(bad.checks.isEmpty());}
    }
    @Test void firstApplicationInitializesLiftBeforeGainReactionAndRefreshDoesNotStackHeight()throws Exception{
        var h=new Harness(view(false,"chorus_d2:elite"));h.apply();h.until(250_000);assertEquals(5,h.moves.size());assertEquals(1.05,h.position.y(),1e-9);assertEquals(.05,h.moves.getLast().command().distance(),1e-9);
        var other=source("other","second-caster",20,1);h.send(SourceChange.bind(other));h.event(other,"apply_suspend");h.until(1_000_000);assertEquals(5,h.moves.size());assertEquals(h.source.origin(),h.status().orElseThrow().origin());assertEquals(6_250_000,h.status().orElseThrow().stacks().getFirst().expiresAt());
        h.send(SourceChange.remove(h.source.instance()));h.until(6_249_999);assertFalse(h.allowed(ActionGate.Kind.VERTICAL_MOTION));h.until(6_250_000);assertTrue(h.allowed(ActionGate.Kind.VERTICAL_MOTION));assertTrue(h.damage.isEmpty());
    }
    @Test void clippedLiftStopsPermanentlyUntilTheCurrentStatusEndsAndCleanseCancelsOwnedWork()throws Exception{
        var h=new Harness(view(false,"chorus_d2:miniboss"));h.ceiling=.6;h.apply();h.until(300_000);assertEquals(3,h.moves.size());assertEquals(.6,h.position.y());assertTrue(h.moves.getLast().clipped());h.apply();h.ceiling=100;h.until(1_000_000);assertEquals(3,h.moves.size());assertFalse(h.allowed(ActionGate.Kind.VERTICAL_MOTION));h.clear();assertTrue(h.allowed(ActionGate.Kind.VERTICAL_MOTION));h.until(5_000_000);assertEquals(3,h.moves.size());assertTrue(h.damage.isEmpty());
        var early=new Harness(view(false,"chorus_d2:elite"));early.apply();early.clear();early.until(1_000_000);assertTrue(early.moves.isEmpty());
    }
    @Test void bossKeepsTheDebuffUntilOneSecondThenSnapsOnceWithOriginalCredit()throws Exception{
        var h=new Harness(view(false,"chorus_d2:boss","chorus_d2:rank_and_file"));h.fragment("own","caster");h.apply();h.send(SourceChange.remove(h.source.instance()));h.until(999_999);assertTrue(h.status().isPresent());assertTrue(h.moves.isEmpty());assertTrue(h.damage.isEmpty());
        h.until(1_000_000);assertTrue(h.status().isEmpty());var snap=h.damage.getFirst();assertEquals(300,snap.amount());assertEquals("target",snap.target());assertEquals(h.source.origin(),snap.source());assertTrue(snap.tags().containsAll(Set.of("chorus:strand","chorus:suspend_boss_snap")));assertEquals("chorus_d2:suspend_snap",snap.damageType());h.until(3_000_000);assertEquals(1,h.damage.size());
        var removed=new Harness(view(false,"chorus_d2:boss"));removed.apply();removed.until(500_000);removed.clear();removed.until(2_000_000);assertTrue(removed.damage.isEmpty());
    }
    @Test void unknownDeadMissingAndUnauthorizedTargetsCannotGainControlOrDamage()throws Exception{
        for(var view:Arrays.asList(view(false),null,new EntityQuery.View(false,false,0,1000,0,Set.of("chorus_d2:boss"),Set.of()))){var h=new Harness(view);h.apply();assertTrue(h.checks.isEmpty());assertTrue(h.status().isEmpty());}
        for(var decision:StatusResult.Decision.values())if(decision!=StatusResult.Decision.ALLOWED){var h=new Harness(view(false,"chorus_d2:boss"));h.decision=decision;h.apply();h.until(2_000_000);assertTrue(h.status().isEmpty());assertTrue(h.damage.isEmpty());assertTrue(h.moves.isEmpty());}
        var player=new Harness(view(true,"chorus_d2:boss"));player.apply();assertEquals(2_000_000,player.checks.getFirst().duration());assertFalse(player.allowed(ActionGate.Kind.VERTICAL_MOTION));player.until(2_000_000);assertTrue(player.damage.isEmpty());
    }
    @Test void sourceDispatchAndCalibrationCannotAccidentallyApplyTwoProviders()throws Exception{
        var h=new Harness(view(false,"chorus_d2:elite"));var second=source("second","caster",10,1);h.send(SourceChange.bind(second));h.apply();assertEquals(1,h.checks.size());
        for(double[] invalid:List.of(new double[]{-1,.25},new double[]{1,0},new double[]{1,65})){
            var bad=new Harness(view(false,"chorus_d2:elite"));var input=source("invalid","caster",invalid[0],invalid[1]);bad.send(SourceChange.bind(input));bad.event(input,"apply_suspend");assertTrue(bad.checks.isEmpty());assertTrue(bad.status().isEmpty());
        }
    }
    @Test void unknownSnapOutcomeStopsAfterTheStatusEndedWithoutRetryingDamage()throws Exception{
        var h=new Harness(view(false,"chorus_d2:boss"));h.apply();h.failDamage=true;assertThrows(IllegalStateException.class,()->h.until(1_000_000));assertTrue(h.status().isEmpty());assertEquals(1,h.damage.size());assertThrows(IllegalStateException.class,()->h.until(2_000_000));assertEquals(1,h.damage.size());
    }
    @Test void suspendContentRoundtripsWithoutNativeHostOrCalibrationDefaults()throws Exception{
        var p=link("suspend","continuity","combat_damage");assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        var definition=p.program().bundles().stream().filter(b->b.id().equals("chorus_d2:suspend_application")).findFirst().orElseThrow();assertEquals(Set.of("lift_height","lift_step","hover_speed"),definition.parameters().keySet());
    }
}
