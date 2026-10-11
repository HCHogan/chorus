package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.finisher.Finisher;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CessationTest {
    static final String D="chorus_d2:";
    static EffectSource fragment(String id,String actor) { return new EffectSource(id,D+"echo_of_cessation",actor,new BuffInstance.Origin(actor,id,"",""),Set.of(),Map.of("burst_radius",new Measure(4,Unit.METER),"receipt_seconds",new Measure(12,Unit.SECOND))); }
    static EffectSource application(String actor) { return new EffectSource("volatile/"+actor,D+"volatile_status_application",actor,new BuffInstance.Origin(actor,"volatile","gun",""),Set.of(),Map.of("combatant_duration",new Measure(10,Unit.SECOND),"guardian_duration",new Measure(6,Unit.SECOND))); }
    static class Harness extends StarvationTest.Harness {
        final List<TargetQuery> queries=new ArrayList<>(); final List<StatusResult.Check> applications=new ArrayList<>(); final List<DamageCommand> damage=new ArrayList<>();
        List<String> neighbors=List.of("near","guardian"); StatusResult.Decision decision=StatusResult.Decision.ALLOWED; Finisher.Decision finisher=Finisher.Decision.ALLOWED;
        boolean unknownStatus,omitBuffs,omitPosition,missingPosition; Finisher.Plan plan;
        Harness() throws Exception { super("volatile","echo_of_cessation","cessation_inputs"); equip(fragment("fragment","player")); equip(FinisherTest.source("player")); entity("near",100,false,Set.of());entity("guardian",100,true,Set.of()); }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            if(r.command() instanceof Finisher.Check q)return new Finisher.Checked(q,finisher);
            if(r.command() instanceof TargetQuery q) {
                queries.add(q); var selected=new ArrayList<TargetQuery.Target>();
                if(q.radius()==4 && q.center() instanceof TargetQuery.PositionCenter pc && pc.position().isPresent())
                    for(String id:neighbors)if(!q.exclude().contains(id))selected.add(new TargetQuery.Target(id,1));
                selected.sort(q.comparator()); return new TargetQuery.Result(q,q.center() instanceof TargetQuery.PositionCenter pc && pc.position().isEmpty()?TargetQuery.Outcome.MISSING_CENTER:TargetQuery.Outcome.AVAILABLE,selected);
            }
            if(r.command() instanceof StatusResult.Check q) {applications.add(q);if(unknownStatus)throw new IllegalStateException("unknown volatile authorization");return new StatusResult.Checked(q,decision);}
            if(r.command() instanceof DamageCommand q) {
                damage.add(q);var observed=omitBuffs?new BuffObservation(now(),Map.of()):BuffObservation.capture(state().buffs(),q.source().owner(),q.target());
                double before=health.get(q.target()),loss=Math.min(before,q.amount());health.put(q.target(),before-loss);
                var positions=omitPosition?Map.<PositionQuery,Optional<WorldPosition>>of():Map.of(new PositionQuery(q.target()),missingPosition?Optional.<WorldPosition>empty():Optional.of(StarvationTest.POINT));
                lastCommand=q;lastReceipt=new DamageReceipt(r.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,loss,before>0&&loss==before?Optional.of("death/"+r.id()):Optional.empty(),false)
                        .withObservedBuffs(observed).withObservedEntities(new EntityObservation(now(),Map.of(q.target(),view(q.target())),positions));
                afterDamage.run();return lastReceipt;
            }
            return super.world(r);
        }
        void input(String kind,String target) { session.start(now(),new RuleEngine.Signal("test:"+kind,new EffectEvent("player",target,FinisherTest.source("player").origin(),Set.of(),Map.of())));healthy(); }
        void begin() { input("begin","enemy");plan=(Finisher.Plan)state().timers().values().stream().map(t->t.signal().payload()).filter(Finisher.Plan.class::isInstance).findFirst().orElseThrow(); }
        void complete() { begin();until(now()+300_000); }
        Optional<BuffInstance> status(String target) {return state().buffs().instances().values().stream().filter(b->b.key().holder().equals(target)&&b.key().definition().equals(D+"volatile")).findFirst();}
        void apply(String actor,String target) {var source=application(actor);equip(source);event(source,target,"apply_volatile");}
        RuleEngine.Signal finishedFact() {
            var e=(EffectEvent)facts().stream().filter(f->f.type().equals("chorus:kill")).findFirst().orElseThrow().payload();var refs=new HashMap<>(e.references());refs.put("finisher_id",plan.id());
            return new RuleEngine.Signal("chorus:finisher",new EffectEvent(e.actor(),e.victim(),e.source(),e.tags(),e.numbers(),e.flags(),refs,e.impact(),e.reactions(),e.proc(),e.observedBuffs(),e.observedEntities()));
        }
    }
    @Test void onlyConfirmedFinisherAppliesSharedVolatileWithActualTargetDurationsAndNoInventedBurstDamage() throws Exception {
        var h=new Harness();h.begin();assertTrue(h.applications.isEmpty());h.until(300_000);
        assertEquals(2,h.applications.size());assertEquals(10_300_000,h.status("near").orElseThrow().deadline());assertEquals(6_300_000,h.status("guardian").orElseThrow().deadline());
        assertEquals(FinisherTest.source("player").origin(),h.status("near").orElseThrow().origin());assertEquals(1,h.damage.size());assertTrue(h.spawns.isEmpty());
        var query=h.queries.getFirst();assertEquals(new TargetQuery.PositionCenter(StarvationTest.POINT),query.center());assertEquals(Set.of("player","enemy"),query.exclude());
    }
    @Test void cancellationOrOrdinaryKillCannotImpersonateACompletedFinisher() throws Exception {
        var h=new Harness();h.begin();h.finisher=Finisher.Decision.DEAD;h.until(50_000);assertTrue(h.applications.isEmpty());h.kill();assertTrue(h.applications.isEmpty()&&h.spawns.isEmpty());
    }
    @Test void duplicateFragmentsAndReplayAfterCleanseRebindAndCheckpointDoNotReapply() throws Exception {
        var h=new Harness();h.equip(fragment("duplicate","player"));h.complete();var fact=h.finishedFact();assertEquals(2,h.applications.size());
        h.input("clear_volatile","near");h.input("clear_volatile","guardian");h.remove("fragment");h.remove("duplicate");h.equip(fragment("again","player"));h.restore();h.replay(List.of(fact));
        assertTrue(h.status("near").isEmpty()&&h.status("guardian").isEmpty());assertEquals(2,h.applications.size());
        h.health.put("enemy",1.0);h.complete();assertEquals(4,h.applications.size());
    }
    @Test void historicalVolatileKillFromAnotherGiverSpawnsBreachAfterLiveStateAndCorpseDisappear() throws Exception {
        var h=new Harness();h.apply("giver","near");h.afterDamage=()->h.kinds.remove("near");h.damage("player","near",200);
        assertTrue(h.status("near").isEmpty());assertEquals(1,h.spawns.size());assertEquals(Optional.of(StarvationTest.POINT),h.spawns.getFirst().position());
        assertEquals("player",h.spawns.getFirst().recipient());assertEquals(new TargetQuery.PositionCenter(StarvationTest.POINT),h.queries.getLast().center());
        h.finish(0,true);assertEquals(170,h.health.get("player"));assertEquals(.025,h.account("class_a"),1e-12);
    }
    @Test void liveStatusesKeepTheirFirstOriginAndDeadlineAndOutlastFragmentRemoval() throws Exception {
        var h=new Harness();h.apply("giver","near");var first=h.status("near").orElseThrow();h.until(500_000);h.complete();assertEquals(first,h.status("near").orElseThrow());h.remove("fragment");
        h.until(6_800_000);assertTrue(h.status("guardian").isEmpty()&&h.status("near").isPresent());h.until(10_000_000);assertTrue(h.status("near").isEmpty());
    }
    @Test void deniedDeadAndMissingRecipientsDoNotReceiveVolatile() throws Exception {
        var h=new Harness();h.decision=StatusResult.Decision.DENIED;h.complete();assertTrue(h.status("near").isEmpty()&&h.status("guardian").isEmpty());
        var other=new Harness();other.health.put("near",0.0);other.kinds.remove("guardian");other.complete();assertTrue(other.applications.isEmpty());
    }
    @Test void sharedBreachCooldownAndFiniteDeathReceiptsDoNotBlockIndependentLaterKills() throws Exception {
        var h=new Harness();h.complete();h.damage("player","near",200);assertEquals(1,h.spawns.size());h.damage("player","guardian",200);var old=h.facts();assertEquals(1,h.spawns.size());
        h.until(5_300_000);h.replay(old);assertEquals(1,h.spawns.size());h.entity("fresh",1,false,Set.of());h.apply("giver","fresh");h.damage("player","fresh",10);assertEquals(2,h.spawns.size());
    }
    @Test void missingHistoricalBuffsOrPositionNeverInventBreachOrFinisherCenter() throws Exception {
        var h=new Harness();h.apply("giver","near");h.omitBuffs=true;h.damage("player","near",200);assertTrue(h.spawns.isEmpty());
        for(boolean unobserved:List.of(false,true)) {var other=new Harness();other.omitPosition=unobserved;other.missingPosition=!unobserved;other.complete();assertTrue(other.applications.isEmpty());}
    }
    @Test void fragmentMayBeEquippedOrRemovedWhileTheAcceptedFinisherIsWaiting() throws Exception {
        var h=new Harness();h.remove("fragment");h.begin();h.equip(fragment("late","player"));h.until(300_000);assertEquals(2,h.applications.size());
        var other=new Harness();other.begin();other.remove("fragment");other.until(300_000);assertTrue(other.applications.isEmpty());
    }
    @Test void unknownStatusOrBreachResultStopsAfterCommittedFactsWithoutRetry() throws Exception {
        var h=new Harness();h.unknownStatus=true;h.begin();assertThrows(IllegalStateException.class,()->h.until(300_000));assertEquals(0,h.health.get("enemy"));assertEquals(1,h.applications.size());assertThrows(IllegalStateException.class,()->h.until(400_000));assertEquals(1,h.applications.size());
        var other=new Harness();other.apply("giver","near");other.failSpawn=true;assertThrows(IllegalStateException.class,()->other.damage("player","near",200));assertEquals(1,other.spawns.size());assertThrows(IllegalStateException.class,()->other.until(100_000));assertEquals(1,other.spawns.size());
    }
}
