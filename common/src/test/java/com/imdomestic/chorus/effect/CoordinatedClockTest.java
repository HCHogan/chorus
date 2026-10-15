package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static com.imdomestic.chorus.effect.ResourceRequestSessionTest.ORIGIN;
import static com.imdomestic.chorus.effect.ResourceRoutingTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.*;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CoordinatedClockTest {
    static final String LOCAL="local";
    static final ResourceState.Key LOCAL_ENERGY=new ResourceState.Key(LOCAL,"test:local");
    static JsonObject remote() {var v=resource();v.addProperty("target","source_owner");return v;}
    static JsonObject scale(JsonObject value,double factor,String unit) {return WorldResourceQueryTest.scale(value,factor,unit);}
    static JsonObject data(boolean recovery) {
        var d=ResourceReadViewTest.data();
        d.getAsJsonArray("resources").add(object("{\"id\":\"test:local\",\"capacity\":2,\"initial\":0,\"rate_profile\":\"test:rate\",\"thresholds\":[0.2,0.4]}"));
        d.getAsJsonArray("profiles").add(WorldResourceQueryTest.profile("rate","charge_fraction_per_second","add"));
        d.getAsJsonArray("profiles").add(WorldResourceQueryTest.profile("healing","damage_per_second","multiply"));
        var source=object("{\"id\":\"test:continuous\"}");
        source.add("modifiers",array(WorldResourceQueryTest.modifier("rate","rate","add",scale(WorldResourceQueryTest.nested(),1,"charge_fraction_per_second"),"on_hit","holder"),
                WorldResourceQueryTest.modifier("healing","healing","multiply",scale(remote(),1,"delta"),"on_hit","holder")));
        var boost=pool("grant_resource","requested",charge(.4));boost.addProperty("target","source_owner");
        source.add("rules",array(rule("boost",array(boost)),rule("probe",array(action("play_cue","cue",new JsonPrimitive("test:external"))))));
        if(recovery){
            var heal=object("{\"id\":\"heal\",\"channel\":\"test:health\",\"profile\":\"test:healing\"}");
            heal.add("rate",scale(WorldResourceQueryTest.nested(),10,"damage_per_second"));heal.add("if",compare(remote(),.2));source.add("health_recovery",array(heal));
        }
        d.getAsJsonArray("bundles").add(source);
        var shield=object("{\"definition\":{\"id\":\"test:shield\",\"version\":\"resource-views\",\"duration\":\"permanent\",\"components\":{\"numbers\":{\"capacity\":{\"initial\":0,\"unit\":\"damage\"}}}},\"shield\":{\"capacity\":\"capacity\"}}");
        shield.getAsJsonObject("shield").add("maximum",scale(remote(),10,"damage"));
        if(recovery){var heal=new JsonObject();heal.add("rate",scale(remote(),20,"damage_per_second"));heal.add("if",compare(remote(),.2));shield.getAsJsonObject("shield").add("recovery",heal);}
        d.add("buffs",array(shield));return d;
    }
    static EffectState initialState(JsonObject data) {
        var program=ResourceReadViewTest.compile(data);var state=initial(0).withResource(new ResourceState(LOCAL_ENERGY,0,2,0))
                .withSource(ResourceReadViewTest.source(LOCAL)).withSource(new EffectSource("continuous","test:continuous",LOCAL,ORIGIN,Set.of()));
        return state.withBuffs(Buffs.grant(state.buffs(),program.buff("test:shield"),LOCAL,LOCAL,ORIGIN,1,1,1_000_000).store());
    }
    record Heal(long time,HealingCommand command) {}
    record Cue(long time,double energy,String cue) {}
    static final class Fixture extends ResourceRoutingTest.Fixture {
        final List<Heal> heals=new ArrayList<>();final List<Cue> cues=new ArrayList<>();boolean failHeal;
        Fixture(boolean recovery){this(data(recovery));}
        Fixture(JsonObject data){this(data,initialState(data));}
        Fixture(JsonObject data,EffectState initial){super(data,initial,empty(50_000_000));host.claim(LOCAL,A);external=this::world;}
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request){
            if(request.command() instanceof HealingCommand heal){heals.add(new Heal(time(),heal));if(failHeal)throw new IllegalStateException("unknown healing");return new HealingReceipt(request.id().toString(),heal,HealingReceipt.Outcome.APPLIED,heal.amount(),heal.amount(),0);}
            var cue=(Action.CueCommand)request.command();cues.add(new Cue(time(),balance(),cue.cue()));return RuleEngine.Empty.INSTANCE;
        }
        long time(){return state(A).buffs().timeMicros();}
        double balance(){return state(A).resources().get(LOCAL_ENERGY).value();}
        double shield(){return state(A).buffs().instances().values().iterator().next().components().numbers().get("capacity");}
        double healed(){return heals.stream().mapToDouble(h->h.command().amount()).sum();}
        void advance(long time){sessions.get(A).observe(time,List.of());}
        void restore(){
            host.drain(10);var checkpoint=host.checkpoint();
            var archive=com.imdomestic.chorus.effect.persistence.CheckpointArchive.coordinated(ResourceCheckpointTest.entries(checkpoint),checkpoint);
            var saved=com.imdomestic.chorus.effect.persistence.CheckpointArchive.decode(archive.encode()).resourceCheckpoint().orElseThrow();
            var policies=new TreeMap<String,ResourceSessionAuthority.RestoreBinding>();
            for(var domain:saved.domains().keySet())policies.put(domain,new ResourceSessionAuthority.RestoreBinding(program,EffectCheckpointTest.clock(),1,r->execute(domain,r)));
            var restored=ResourceSessionAuthority.restore(saved,policies);host=restored.authority();
            restored.bindings().forEach((domain,binding)->sessions.put(domain,binding.session()));
        }
    }
    @Test void allContinuousRatesUseTheRemoteBalanceButAdvanceOnlyTheLocalDomain() {
        var f=new Fixture(true);f.advance(100_000);
        assertEquals(.04,f.balance(),1e-12);assertEquals(.56,f.healed(),1e-12);assertEquals(.8,f.shield(),1e-12);
        assertEquals(List.of(50_000L,100_000L),f.heals.stream().map(Heal::time).toList());
        assertEquals(.4,f.energy());assertEquals(50_000_000,f.state(B).buffs().timeMicros());assertFalse(f.state(A).resources().containsKey(ENERGY));
        assertEquals(100_000,f.host.snapshot().directory().owner(LOCAL).timeMicros());assertFalse(f.sessions.get(B).inboxPending());
    }
    @Test void timerReactionChangesRemoteRatesBeforeTheNextSegmentWithoutDrainingTheRecipient() {
        var d=data(true);var initial=initialState(d).schedule(new EffectState.Timer("boost",75_000,0,1,new RuleEngine.Signal("test:boost",new EffectEvent(LOCAL,LOCAL,ORIGIN,Set.of(),Map.of())),Optional.empty()));
        var f=new Fixture(d,initial);f.advance(150_000);
        assertEquals(.09,f.balance(),1e-12);assertEquals(1.5,f.healed(),1e-12);assertEquals(1.8,f.shield(),1e-12);assertEquals(.8,f.energy());
        assertEquals(List.of(50_000L,75_000L,100_000L,150_000L),f.heals.stream().map(Heal::time).toList());
        assertTrue(f.sessions.get(B).inboxPending());assertFalse(f.host.drain(10).pending());
    }
    @Test void thresholdFactsSettleBeforeTheRequestedExternalEvent() {
        var d=data(false);var source=d.getAsJsonArray("bundles").get(1).getAsJsonObject();
        source.getAsJsonArray("rules").add(object("{\"id\":\"changed\",\"on\":\"chorus:resource_changed\",\"do\":[{\"type\":\"chorus:play_cue\",\"cue\":\"test:threshold\"}]}"));
        var f=new Fixture(d);f.sessions.get(A).start(1_250_000,new RuleEngine.Signal("test:probe",new EffectEvent(LOCAL,LOCAL,ORIGIN,Set.of(),Map.of())));
        assertEquals(List.of(500_000L,1_000_000L,1_250_000L,1_250_000L),f.cues.stream().map(Cue::time).toList());
        assertEquals(List.of(.2,.4,.5,.5),f.cues.stream().map(Cue::energy).toList());assertEquals("test:external",f.cues.getLast().cue());
    }
    @Test void expiringRecoveryRetainsTheFinalFractionAndDoesNotCreditFutureTime() {
        var d=data(true);d.getAsJsonArray("bundles").get(1).getAsJsonObject().remove("health_recovery");
        var buffBundle=object("{\"id\":\"test:recovering\",\"scope\":\"buff\"}");
        var heal=object("{\"id\":\"heal\",\"channel\":\"test:health\"}");heal.add("rate",scale(remote(),10,"damage_per_second"));buffBundle.add("health_recovery",array(heal));
        d.getAsJsonArray("bundles").add(buffBundle);var shield=d.getAsJsonArray("buffs").get(0).getAsJsonObject();shield.addProperty("bundle","test:recovering");shield.getAsJsonObject("definition").addProperty("duration",.070001);
        var initial=initialState(d);var definition=ResourceReadViewTest.compile(d).buff("test:shield");initial=initial.withBuffs(Buffs.grant(BuffStore.empty(),definition,LOCAL,LOCAL,ORIGIN,1,1,70_001).store());
        var f=new Fixture(d,initial);f.advance(200_000);assertEquals(.070001*4,f.healed(),1e-12);assertTrue(f.state(A).buffs().instances().isEmpty());
        assertEquals(List.of(50_000L,70_001L),f.heals.stream().map(Heal::time).toList());assertEquals(.08,f.balance(),1e-12);
        f.advance(300_000);assertEquals(2,f.heals.size());
    }
    @Test void frozenPolicyEvaluatesOnceAndCannotBeReusedForAnotherSegmentOrChangedAccount() {
        var f=new Fixture(true);var view=f.host.readView(A);var clock=f.program.continuousClock(view,EffectCheckpointTest.clock());
        f.host.grant(ENERGY,.4,.4);assertEquals(50_000,clock.nextDeadline(view.state()));var result=clock.advance(view.state(),50_000);
        assertEquals(.02,result.state().resources().get(LOCAL_ENERGY).value(),1e-12);assertEquals(.4,result.state().buffs().instances().values().iterator().next().components().numbers().get("capacity"),1e-12);
        assertThrows(IllegalArgumentException.class,()->clock.advance(result.state(),100_000));assertThrows(IllegalArgumentException.class,()->clock.resourceRate(view.state(),new ResourceState(LOCAL_ENERGY,.1,2,0)));
        assertEquals(0,f.balance());assertEquals(0,f.shield());
        var count=new AtomicInteger();var custom=new EffectClock((_,_)->{count.incrementAndGet();return new EffectClock.Rate(1,List.of());}).frozen(view.state());
        custom.nextDeadline(view.state());custom.advance(view.state(),1);assertEquals(view.state().resources().size(),count.get());
    }
    @Test void pureRateQueriesRejectForeignAccountsMissingViewsAndDifferentCatalogues() {
        var f=new Fixture(true);var view=f.host.readView(A);
        assertThrows(IllegalStateException.class,()->f.program.resourceRate(view,view.account(ENERGY)));
        assertThrows(IllegalStateException.class,()->f.program.resourceRate(f.state(A),f.state(A).resources().get(LOCAL_ENERGY)));
        assertThrows(IllegalStateException.class,()->f.program.recoveryOffers(f.state(A)));assertThrows(IllegalStateException.class,()->f.program.shieldRecoveryOffers(f.state(A)));
        var d=data(true);d.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("initial",1);var other=ResourceReadViewTest.compile(d).withResourceRouting();
        assertThrows(IllegalArgumentException.class,()->other.continuousClock(view,EffectCheckpointTest.clock()));
    }
    @Test void restoredSessionsReconnectTheirClockAndDoNotReplayEarlierRecovery() {
        var f=new Fixture(true);f.advance(70_001);double before=f.healed();f.restore();assertEquals(before,f.healed());f.advance(100_000);
        assertEquals(.56,f.healed(),1e-12);assertEquals(.04,f.balance(),1e-12);assertEquals(.8,f.shield(),1e-12);
        assertEquals(List.of(50_000L,70_001L,100_000L),f.heals.stream().map(Heal::time).toList());
    }
    @Test void transferredRechargeRunsAtTheNewOwnerClockExactlyOnce() {
        var d=data(false);d.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("base_rate",.1);var f=new Fixture(d);
        f.advance(1_000_000);assertEquals(.4,f.energy());f.sessions.get(B).observe(51_000_000,List.of());assertEquals(.5,f.energy(),1e-12);
        f.move(A);f.advance(2_000_000);assertEquals(.6,f.energy(),1e-12);assertFalse(f.state(B).resources().containsKey(ENERGY));assertEquals(2_000_000,f.host.snapshot().directory().owner(HOLDER).timeMicros());
    }
    @Test void unknownHealingKeepsIntegratedResourcesAndShieldWritesAndStopsFurtherTime() {
        var f=new Fixture(true);f.failHeal=true;assertThrows(IllegalStateException.class,()->f.advance(100_000));
        assertEquals(50_000,f.time());assertEquals(.02,f.balance(),1e-12);assertEquals(.4,f.shield(),1e-12);assertEquals(1,f.heals.size());
        assertThrows(IllegalStateException.class,()->f.advance(100_000));assertThrows(IllegalStateException.class,()->f.sessions.get(A).checkpoint());assertEquals(1,f.heals.size());
    }
    @Test void pendingInboxReactionsRunAtTheirOriginalTimeBeforeClockPlanning() {
        var d=data(true);var source=d.getAsJsonArray("bundles").get(1).getAsJsonObject();var grant=pool("grant_resource","requested",charge(.4));grant.addProperty("target","source_owner");source.getAsJsonArray("rules").add(rule("queued",array(grant)));
        var f=new Fixture(d);var before=f.sessions.get(A).state();var event=new EffectEvent(HOLDER,HOLDER,ORIGIN,Set.of(),Map.of());
        EffectSession.commitDomains(List.of(f.sessions.get(A).prepareInboxDomain(before,f.state(A),List.of(new RuleEngine.Signal("test:queued",event)))));
        assertThrows(IllegalArgumentException.class,()->f.advance(-1));assertTrue(f.sessions.get(A).inboxPending());assertEquals(.4,f.energy());
        f.advance(100_000);assertEquals(.8,f.energy());assertEquals(.08,f.balance(),1e-12);assertEquals(1.44,f.healed(),1e-12);assertEquals(1.6,f.shield(),1e-12);
        assertFalse(f.sessions.get(A).inboxPending());
    }
    @Test void unboundCoordinatedSessionsCannotAdvanceAndASecondAuthorityCannotStealTheClock() {
        var d=data(false);var p=ResourceReadViewTest.compile(d).withResourceRouting();var session=new EffectSession(p,EffectCheckpointTest.clock(),1,initialState(d),_->{throw new AssertionError();});
        assertThrows(IllegalStateException.class,()->session.observe(1,List.of()));assertEquals(0,session.state().engine().domain().buffs().timeMicros());
        var host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,Map.of(A,new ResourceSessionAuthority.Binding(p,session)));
        session.observe(1,List.of());assertEquals(1,session.state().engine().domain().buffs().timeMicros());
        assertThrows(IllegalStateException.class,()->new ResourceSessionAuthority(host.snapshot().directory(),Map.of(A,new ResourceSessionAuthority.Binding(p,session))));
    }
    @Test void clockCaptureCapabilityIsLimitedToTheExactSessionAndPlanningCallback() {
        var d=data(false);var p=ResourceReadViewTest.compile(d).withResourceRouting();var initial=initialState(d);
        var session=new EffectSession(p,EffectCheckpointTest.clock(),1,initial,_->{throw new AssertionError();});
        var other=new EffectSession(p,EffectCheckpointTest.clock(),1,initial,_->{throw new AssertionError();});var tokens=new ArrayList<EffectSession.ClockOperation>();
        session.bindClockCapture(operation->{
            tokens.add(operation);var before=session.clockState(operation);assertSame(initial,before.engine().domain());
            assertThrows(IllegalStateException.class,()->other.clockState(operation));assertThrows(IllegalStateException.class,session::checkpoint);
            assertThrows(IllegalStateException.class,()->session.observe(1,List.of()));
            return new ResourceReadView(new ResourceAuthority.Worlds(ResourceAuthority.Directory.EMPTY,Map.of(A,initial)),A,p.program());
        });
        session.observe(1,List.of());assertEquals(1,tokens.size());assertThrows(IllegalStateException.class,()->session.clockState(tokens.getFirst()));
        assertEquals(1,session.checkpoint().timeline().timeMicros());assertThrows(IllegalStateException.class,()->session.bindClockCapture(_->{throw new AssertionError();}));
    }
    @Test void failedRatePreparationPublishesNoPartialAdvanceAndStructuralClocksCannotBeUsedAsFallbacks() {
        var d=data(false);var bad=action("event_number","name",new JsonPrimitive("missing"));bad.addProperty("unit","charge_fraction_per_second");
        d.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().add("value",bad);
        var f=new Fixture(d);assertThrows(IllegalArgumentException.class,()->f.advance(1));assertEquals(0,f.time());assertEquals(0,f.balance());assertEquals(.4,f.energy());assertTrue(f.sessions.get(A).state().idle());
        assertEquals(0,f.sessions.get(A).checkpoint().timeline().timeMicros());
        var p=ResourceReadViewTest.compile(data(false)).withResourceRouting();var engine=p.engine(EffectCheckpointTest.clock(),1);var initial=initialState(data(false));
        var result=engine.transition(engine.initial(initial),new RuleEngine.Start(1,List.of()));
        for(int i=0;i<10&&result.needsPump();i++)result=engine.transition(result.state(),RuleEngine.Pump.INSTANCE);
        assertTrue(result.state().engine().failure().isPresent());assertSame(initial,result.state().engine().domain());
    }
}
