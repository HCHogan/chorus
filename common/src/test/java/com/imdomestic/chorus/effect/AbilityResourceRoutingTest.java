package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static com.imdomestic.chorus.effect.ResourceRoutingTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AbilityResourceRoutingTest {
    static final String SLOT="test:grenade", FRIEND="friend";
    record Heal(String domain, HealingCommand command) {}
    static final class Fixture {
        final CompiledEffects program;
        final Map<String,EffectSession> sessions=new TreeMap<>();
        final List<ResourceRequests.Receipt> replies=new ArrayList<>();
        final List<Heal> heals=new ArrayList<>();
        ResourceSessionAuthority host;
        Function<ResourceRequests.Receipt,RuleEngine.ActionResult> callback=r->r;
        boolean unknownHeal;
        int cast;
        Fixture() throws Exception {this(EffectTestSupport.json("ability_cost_selection"),true,.4);}
        Fixture(JsonObject data,boolean initialize,double friendEnergy) {
            program=EffectTestSupport.compile(data).withResourceRouting();
            var a=empty(0).withAbilities(HOLDER,initialize?new AbilityLoadout(Map.of(SLOT,"test:a")):AbilityLoadout.EMPTY);
            if(initialize)for(var definition:program.program().resources())a=a.withResource(definition.initialize(HOLDER,0));
            a=a.withResource(new ResourceState(new ResourceState.Key(FRIEND,"test:energy"),friendEnergy,2,0));
            var b=empty(50_000_000).withAbilities(HOLDER,new AbilityLoadout(Map.of(SLOT,"test:b")));
            Map.of(A,a,B,b).forEach((domain,state)->sessions.put(domain,new EffectSession(program,EffectCheckpointTest.clock(),1,state,r->world(domain,r))));
            connect(ResourceAuthority.Directory.EMPTY);host.claim(HOLDER,A);host.claim(FRIEND,A);move(B);
        }
        void connect(ResourceAuthority.Directory directory) {
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();sessions.forEach((id,s)->bindings.put(id,new ResourceSessionAuthority.Binding(program,s)));host=new ResourceSessionAuthority(directory,bindings);
        }
        RuleEngine.ActionResult world(String domain,RuleEngine.WorldRequest request) {
            if(request.command() instanceof ResourceRequests.Request) {
                var receipt=host.execute(domain,request);assertSame(receipt,host.execute(domain,request));
                assertEquals(receipt,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(receipt),ResourceRequests.Receipt.class));
                replies.add(receipt);return callback.apply(receipt);
            }
            var heal=(HealingCommand)request.command();heals.add(new Heal(domain,heal));
            if(unknownHeal)throw new IllegalStateException("unknown ability world write");
            return new HealingReceipt(request.id().toString(),heal,HealingReceipt.Outcome.APPLIED,heal.amount(),heal.amount(),0);
        }
        EffectState state(String domain) {return sessions.get(domain).state().engine().domain();}
        String owner() {return host.snapshot().directory().owner(HOLDER).lease().domain();}
        double energy(String id) {return host.snapshot().directory().owner(HOLDER).account("test:"+id).value();}
        void send(String caller,RuleEngine.Signal signal) {sessions.get(caller).start(state(caller).buffs().timeMicros(),signal);}
        void choose(Map<String,String> choices) {
            var before=state(owner()).abilities().getOrDefault(HOLDER,AbilityLoadout.EMPTY);
            send(A,new AbilityChange(HOLDER,before,new AbilityLoadout(choices)).signal());drain();
        }
        void choose(String id) {choose(Map.of(SLOT,"test:"+id));}
        void bind(String domain,String id) {send(domain,SourceChange.bind(new EffectSource(id,"test:"+id,HOLDER,new BuffInstance.Origin(HOLDER,id,"",""),Set.of())));}
        AbilityUse.Request request(String slot,EffectEvent input) {return new AbilityUse.Request(HOLDER,slot,"cast/"+(++cast),input);}
        EffectEvent input(String victim) {return new EffectEvent(HOLDER,victim,new BuffInstance.Origin(HOLDER,"input","",""),Set.of(),Map.of(),Map.of("on_ground",false),Map.of());}
        AbilityUse.Receipt use(String caller,String slot,EffectEvent input) {send(caller,request(slot,input).signal());return replies.stream().filter(AbilityResources.Used.class::isInstance).map(AbilityResources.Used.class::cast).toList().getLast().result();}
        AbilityUse.Receipt use() {return use(A,SLOT,input(HOLDER));}
        void drain() {assertFalse(host.drain(10).pending());}
        void move(String domain) {host.commit(host.prepareTransfer(HOLDER,domain));}
        void restore() {
            var directory=host.snapshot().directory();
            for(var domain:List.of(A,B)) {
                var saved=EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(sessions.get(domain).checkpoint()));
                sessions.put(domain,EffectSession.restore(program,EffectCheckpointTest.clock(),1,saved,r->world(domain,r)));
            }
            connect(directory);
        }
    }
    @Test void migratedSelectionReplacementPaymentAndOnUseAllBelongToTheCurrentOwner() throws Exception {
        var f=new Fixture();f.bind(B,"convert");f.bind(B,"discount");
        var result=f.use();assertEquals("test:b",result.base());assertEquals("test:converted",result.resolved());assertEquals(.25,result.cost().orElseThrow().receipt().paid());
        assertEquals(1.75,f.energy("other"));assertEquals(2,f.energy("energy"));assertTrue(ResourceProjection.accounts(f.state(A),HOLDER).isEmpty());assertTrue(f.heals.isEmpty());
        var facts=f.sessions.get(B).state().engine().facts();assertEquals(List.of("chorus:resource_spent","chorus:resource_changed",AbilityUse.STARTED,"chorus:ability_used"),facts.stream().map(e->e.signal().type()).toList());
        var started=(AbilityUse.Started)facts.get(2).signal().payload();assertEquals(A,started.event().references().get("resource_caller_domain"));assertEquals(B,started.event().references().get("resource_recipient_domain"));
        assertEquals(result,started.receipt());assertEquals(50_000_000,facts.get(2).timeMicros());
        assertThrows(IllegalStateException.class,()->f.move(A));f.drain();assertEquals(List.of(B),f.heals.stream().map(Heal::domain).toList());assertEquals(4,f.heals.getFirst().command().amount());
    }
    @Test void staleCallerProvidersCannotReplaceTheOwnersAbility() throws Exception {
        var f=new Fixture();f.bind(A,"convert");var result=f.use();assertEquals("test:b",result.resolved());assertEquals(.5,result.cost().orElseThrow().receipt().paid());
        f.drain();assertEquals(2,f.heals.getFirst().command().amount());assertEquals(B,f.heals.getFirst().domain());
        assertThrows(IllegalStateException.class,()->f.program.useAbility(f.state(A),f.request(SLOT,f.input(HOLDER))));
        assertThrows(IllegalStateException.class,()->f.program.abilityInputAllowed(f.state(A),f.request(SLOT,f.input(HOLDER))));
    }
    @Test void selectingAtTheOwnerInitializesOnceAndEmitsTypedIntrinsicSourceFacts() throws Exception {
        var data=EffectTestSupport.json("ability_cost_selection");
        data.getAsJsonArray("abilities").get(0).getAsJsonObject().add("effects",object("{\"passive\":{\"bundle\":\"test:passive\"}}"));
        data.getAsJsonArray("bundles").add(object("{\"id\":\"test:passive\",\"rules\":[{\"id\":\"start\",\"on\":\"chorus:source_attached\",\"do\":[{\"type\":\"chorus:spend_resource\",\"resource\":\"test:energy\",\"amount\":{\"type\":\"chorus:constant\",\"value\":0.25,\"unit\":\"charge_fraction\"},\"payment\":\"start\"}]}]}"));
        var f=new Fixture(data,false,.4);f.choose("a");
        assertEquals(1.75,f.energy("energy"));assertEquals(50_000_000,f.state(B).resources().get(ENERGY).timeMicros());
        assertEquals(1,f.state(B).sources().values().stream().filter(s->s.bundle().equals("test:passive")).count());
        assertTrue(ResourceProjection.accounts(f.state(A),HOLDER).isEmpty());assertTrue(f.state(A).sources().isEmpty());
        f.use();f.drain();assertEquals(.75,f.energy("energy"));f.choose("a");assertEquals(.75,f.energy("energy"));
        assertTrue(((AbilityResources.Selected)f.replies.getLast()).facts().isEmpty());f.choose("b");assertTrue(f.state(B).sources().isEmpty());assertEquals(.75,f.energy("energy"));
    }
    @Test void staleOrInvalidSelectionCannotPartiallyChangeSourcesOrInitializeAccounts() throws Exception {
        var f=new Fixture();var before=f.state(B);
        assertThrows(IllegalStateException.class,()->f.send(A,new AbilityChange(HOLDER,new AbilityLoadout(Map.of(SLOT,"test:a")),AbilityLoadout.EMPTY).signal()));
        assertEquals(before,f.state(B));
        var invalid=new Fixture(EffectTestSupport.json("ability_cost_selection"),false,.4);var untouched=invalid.state(B);
        var chosen=new AbilityLoadout(Map.of(SLOT,"test:a","test:invalid_slot","test:missing"));
        assertThrows(IllegalArgumentException.class,()->invalid.send(A,new AbilityChange(HOLDER,untouched.abilities().get(HOLDER),chosen).signal()));
        assertEquals(untouched,invalid.state(B));assertTrue(ResourceProjection.accounts(invalid.state(A),HOLDER).isEmpty());
    }
    @Test void crossSlotCostUsesTheOwnersBaseSelectionAndNotTheOtherSlotsOverrides() throws Exception {
        var f=new Fixture(EffectTestSupport.json("ability_cross_slot_cost"),true,.4);
        f.choose(Map.of(SLOT,"test:b","test:air_move","test:air_plain"));f.bind(B,"chain");
        var result=f.use(A,"test:air_move",f.input(HOLDER));assertEquals("test:air_plain",result.resolved());assertEquals(.5,result.cost().orElseThrow().receipt().paid());
        assertEquals(1.5,f.energy("other"));assertEquals(2,f.energy("energy"));f.drain();assertEquals("test:air_plain",f.heals.getFirst().command().source().ability());
    }
    static JsonObject remoteConditions() throws Exception {
        var d=EffectTestSupport.json("ability_cost_selection");var remote=resource();remote.addProperty("target","victim");
        var condition=compare(remote,.3);
        d.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("ability_overrides").get(0).getAsJsonObject().add("if",condition);
        var converted=d.getAsJsonArray("abilities").get(3).getAsJsonObject();converted.add("if",condition);
        var parameter=object("{\"type\":\"chorus:scale\",\"factor\":10,\"from\":\"charge_fraction\",\"to\":\"damage\"}");parameter.add("of",remote);
        var params=new JsonObject();var amount=new JsonObject();amount.add("value",parameter);params.add("amount",amount);converted.add("parameters",params);
        var value=action("event_number","name",new JsonPrimitive("param.amount"));value.addProperty("unit","damage");converted.add("on_use",array(action("heal","amount",value)));
        var discount=d.getAsJsonArray("bundles").asList().stream().map(JsonElement::getAsJsonObject).filter(b->b.get("id").getAsString().equals("test:discount")).findFirst().orElseThrow();discount.getAsJsonArray("modifiers").get(0).getAsJsonObject().add("if",condition);
        var gate=object("{\"id\":\"test:remote_gate\",\"action_gates\":[{\"id\":\"gate\",\"action\":\"ability_use\"}]}");
        var denial=compare(remote,.3);denial.addProperty("op","lt");gate.getAsJsonArray("action_gates").get(0).getAsJsonObject().add("if",denial);d.getAsJsonArray("bundles").add(gate);
        return d;
    }
    @Test void replacementsConditionsParametersCostProfilesAndInputGatesShareTheAuthorityView() throws Exception {
        var f=new Fixture(remoteConditions(),true,.4);f.choose("a");f.bind(B,"convert");f.bind(B,"discount");f.bind(B,"remote_gate");
        var input=f.input(FRIEND);assertTrue(f.program.abilityInputAllowed(f.host.readView(A),f.request(SLOT,input)));
        var result=f.use(A,SLOT,input);assertEquals("test:converted",result.resolved());assertEquals(.5,result.cost().orElseThrow().receipt().paid());f.drain();assertEquals(4,f.heals.getFirst().command().amount());
        var restricted=new Fixture(remoteConditions(),true,.1);restricted.bind(B,"remote_gate");
        assertFalse(restricted.program.abilityInputAllowed(restricted.host.readView(A),restricted.request(SLOT,restricted.input(FRIEND))));
        var rejected=restricted.use(A,SLOT,restricted.input(FRIEND));assertEquals(AbilityUse.Outcome.RESTRICTED,rejected.outcome());assertTrue(rejected.cost().isEmpty());assertEquals(2,restricted.energy("other"));assertFalse(restricted.sessions.get(B).inboxPending());
    }
    @Test void rejectionsNeverEmitStartOrRunEffectsAndFreeAcceptanceHasNoSpendFacts() throws Exception {
        var f=new Fixture();f.choose("a");f.use();f.drain();f.use();f.drain();var rejected=f.use();
        assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,rejected.outcome());assertEquals(0,rejected.cost().orElseThrow().receipt().paid());assertEquals(2,f.heals.size());assertFalse(f.sessions.get(B).inboxPending());
        f.choose("zero");assertEquals(AbilityUse.Outcome.ACCEPTED,f.use().outcome());assertEquals(List.of(AbilityUse.STARTED,"chorus:ability_used"),f.sessions.get(B).state().engine().facts().stream().map(e->e.signal().type()).toList());f.drain();assertEquals(0,f.energy("energy"));
        f.choose(Map.of());assertEquals(AbilityUse.Outcome.EMPTY_SLOT,f.use().outcome());assertFalse(f.sessions.get(B).inboxPending());
        f.choose("free");f.bind(B,"missing_override");assertEquals(AbilityUse.Outcome.NO_BASE_COST,f.use().outcome());assertFalse(f.sessions.get(B).inboxPending());
    }
    @Test void failedConditionAndConflictingReplacementsRejectBeforeParameterEvaluation() throws Exception {
        var d=remoteConditions();var converted=d.getAsJsonArray("abilities").get(3).getAsJsonObject();
        converted.add("parameters",object("{\"missing\":{\"value\":{\"type\":\"chorus:event_number\",\"name\":\"missing\",\"unit\":\"damage\"}},\"amount\":{\"value\":{\"type\":\"chorus:constant\",\"value\":4,\"unit\":\"damage\"}}}"));
        var f=new Fixture(d,true,.1);f.choose("converted");
        assertEquals(AbilityUse.Outcome.CONDITION,f.use(A,SLOT,f.input(FRIEND)).outcome());assertEquals(2,f.energy("converted_energy"));assertFalse(f.sessions.get(B).inboxPending());
        var conflict=EffectTestSupport.json("ability_cost_selection");
        conflict.getAsJsonArray("bundles").add(object("{\"id\":\"test:conflict\",\"ability_overrides\":[{\"id\":\"a\",\"slot\":\"test:grenade\",\"replace_with\":\"test:a\",\"priority\":10},{\"id\":\"b\",\"slot\":\"test:grenade\",\"replace_with\":\"test:b\",\"priority\":10}]}"));
        var c=new Fixture(conflict,true,.4);c.bind(B,"conflict");assertEquals(AbilityUse.Outcome.CONFLICT,c.use().outcome());assertFalse(c.sessions.get(B).inboxPending());assertEquals(2,c.energy("other"));
    }
    @Test void retainedRefundKeepsItsIssuerAcrossReselectionRestoreAndAnotherResourceTransfer() throws Exception {
        var f=new Fixture();f.choose("a");f.bind(B,"refund_override");var accepted=f.use();assertEquals(1,accepted.cost().orElseThrow().receipt().paid());f.drain();
        f.choose("b");f.move(A);f.restore();f.sessions.get(B).observe(50_100_000,List.of());f.drain();
        assertEquals(2,f.energy("energy"));assertEquals(2,f.energy("other"));assertTrue(f.state(B).retainedCosts().isEmpty());assertTrue(ResourceProjection.accounts(f.state(B),HOLDER).isEmpty());
    }
    @Test void sameDomainAcceptanceExecutesLocallyAndUsesDomainScopedPaymentIdentity() throws Exception {
        var remote=new Fixture();var local=new Fixture();var a=remote.use();var b=local.use(B,SLOT,local.input(HOLDER));
        assertNotEquals(a.cost().orElseThrow().receipt().operation(),b.cost().orElseThrow().receipt().operation());assertEquals(1,local.heals.size());
        assertEquals(B,local.heals.getFirst().domain());assertFalse(local.sessions.get(B).inboxPending());remote.drain();
    }
    @Test void unknownAcceptanceKeepsPaymentAndQueuedFactsWithoutReplayingTheCast() throws Exception {
        var f=new Fixture();f.callback=r->{if(r instanceof AbilityResources.Used)throw new IllegalStateException("unknown after paid acceptance");return r;};
        assertThrows(IllegalStateException.class,f::use);assertEquals(1.5,f.state(B).resources().get(new ResourceState.Key(HOLDER,"test:other")).value());
        assertEquals(4,f.sessions.get(B).state().engine().facts().size());assertTrue(f.heals.isEmpty());assertThrows(IllegalStateException.class,()->f.sessions.get(A).checkpoint());
        assertThrows(IllegalStateException.class,f::use);assertEquals(1,f.replies.stream().filter(AbilityResources.Used.class::isInstance).count());
    }
    @Test void destinationMustUseCoordinatedCompilationBeforeAcceptingAnAbility() throws Exception {
        var f=new Fixture();var directory=f.host.snapshot().directory();var before=f.state(B);
        var local=new CompiledEffects(f.program.program());
        var replacement=new EffectSession(local,EffectCheckpointTest.clock(),1,before,r->{throw new AssertionError("Unexpected local ability execution");});
        // A fresh coordinator owns fresh sessions; the old authority still owns A's clock capture.
        var saved=f.sessions.get(A).checkpoint();
        f.sessions.put(A,EffectSession.restore(f.program,EffectCheckpointTest.clock(),1,saved,r->f.world(A,r)));
        f.sessions.put(B,replacement);
        f.host=new ResourceSessionAuthority(directory,Map.of(A,new ResourceSessionAuthority.Binding(f.program,f.sessions.get(A)),B,new ResourceSessionAuthority.Binding(local,replacement)));
        assertThrows(IllegalStateException.class,f::use);assertEquals(before,f.state(B));assertFalse(replacement.inboxPending());assertTrue(f.heals.isEmpty());
    }
    @Test void unknownSelectionResultPreservesTheCommittedLoadoutAndInitializedAccounts() throws Exception {
        var f=new Fixture(EffectTestSupport.json("ability_cost_selection"),false,.4);
        f.callback=r->{if(r instanceof AbilityResources.Selected)throw new IllegalStateException("unknown after selection");return r;};
        assertThrows(IllegalStateException.class,()->f.choose("a"));
        assertEquals("test:a",f.state(B).abilities().get(HOLDER).slots().get(SLOT));assertEquals(2,f.state(B).resources().get(ENERGY).value());
        assertTrue(ResourceProjection.accounts(f.state(A),HOLDER).isEmpty());assertTrue(f.sessions.get(B).inboxPending());assertThrows(IllegalStateException.class,()->f.sessions.get(A).checkpoint());
    }
    @Test void failureInTheAcceptedAbilityDoesNotRefundOrRetryTheAlreadyPaidCost() throws Exception {
        var f=new Fixture();f.use();f.unknownHeal=true;assertThrows(IllegalStateException.class,f::drain);
        assertEquals(1.5,f.state(B).resources().get(new ResourceState.Key(HOLDER,"test:other")).value());assertEquals(1,f.heals.size());
        assertThrows(IllegalStateException.class,()->f.sessions.get(B).checkpoint());assertThrows(IllegalStateException.class,()->f.sessions.get(B).observe(50_100_000,List.of()));assertEquals(1,f.heals.size());
    }
}
