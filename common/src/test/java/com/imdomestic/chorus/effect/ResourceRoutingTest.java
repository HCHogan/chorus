package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static com.imdomestic.chorus.effect.ResourceRequestSessionTest.ORIGIN;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.AbilityLoadout;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ResourceRoutingTest {
    static final String SLOT = ResourceReadViewTest.SLOT;
    static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    static JsonObject number(double value, String unit) { return object("{\"type\":\"chorus:constant\",\"value\":" + value + ",\"unit\":\"" + unit + "\"}"); }
    static JsonObject charge(double value) { return number(value, "charge_fraction"); }
    static JsonObject resource() { return object("{\"type\":\"chorus:resource\",\"resource\":\"test:energy\"}"); }
    static JsonObject result(String binding, String field) { return object("{\"type\":\"chorus:result\",\"binding\":\""+binding+"\",\"field\":\""+field+"\"}"); }
    static JsonObject action(String name, String field, JsonElement value) {
        var a=object("{\"type\":\"chorus:"+name+"\"}"); if(field!=null)a.add(field,value);return a;
    }
    static JsonObject pool(String name,String field,JsonElement value) {var a=action(name,field,value);a.addProperty("resource","test:energy");return a;}
    static JsonObject bind(JsonObject action,String name) {var result=new JsonObject();result.add("action",action);result.addProperty("as",name);return result;}
    static JsonObject spend(double amount) {var a=pool("spend_resource","amount",charge(amount));a.addProperty("payment","pay");return bind(a,"paid");}
    static JsonObject refund(double amount) {var a=action("refund_cost","fraction",number(amount,"multiplier"));a.addProperty("cost","paid");return a;}
    static JsonObject compare(JsonObject value,double against) {var c=action("compare","left",value);c.addProperty("op","gt");c.add("right",charge(against));return c;}
    static JsonObject rule(String id,JsonArray steps) {var r=object("{\"id\":\""+id+"\",\"on\":\"test:"+id+"\"}");r.add("do",steps);return r;}
    static JsonArray array(JsonObject... values) {var a=new JsonArray();for(var v:values)a.add(v);return a;}
    static JsonObject data(JsonObject... rules) {
        var d=ResourceReadViewTest.data();var bundle=d.getAsJsonArray("bundles").get(0).getAsJsonObject();bundle.add("rules",array(rules));return d;
    }
    static class Fixture {
        final CompiledEffects program;
        final Map<String,EffectSession> sessions=new TreeMap<>();
        ResourceSessionAuthority host;
        final List<ResourceRoutes.Receipt> replies=new ArrayList<>();
        Function<ResourceRoutes.Receipt,RuleEngine.ActionResult> callback=r->r;
        Function<RuleEngine.WorldRequest,RuleEngine.ActionResult> external=r->{throw new AssertionError("Unexpected non-resource request");};
        Fixture(JsonObject data) {this(data,initial(0),empty(50_000_000));}
        Fixture(JsonObject data,EffectState a,EffectState b) {
            program=ResourceReadViewTest.compile(data).withResourceRouting();
            var source=ResourceReadViewTest.source(HOLDER);
            Map.of(A,a.withSource(source),B,b.withSource(source)).forEach((domain,state)->sessions.put(domain,new EffectSession(program,EffectCheckpointTest.clock(),1,state,r->execute(domain,r))));
            connect(ResourceAuthority.Directory.EMPTY);host.claim(HOLDER,A);move(B);
        }
        void connect(ResourceAuthority.Directory directory) {
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();sessions.forEach((id,s)->bindings.put(id,new ResourceSessionAuthority.Binding(program,s)));host=new ResourceSessionAuthority(directory,bindings);
        }
        RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request) {
            if (!(request.command() instanceof ResourceRoutes.Request)) return external.apply(request);
            var view=host.readView(domain,request);var frame=sessions.get(domain).state().engine().frames().getFirst();
            var forged=new RuleEngine.WorldRequest(request.id(),new ResourceRoutes.Request("test:forged"));
            assertThrows(IllegalArgumentException.class,()->program.prepareResourceRoute(view,frame,forged));
            var receipt=(ResourceRoutes.Receipt)host.execute(domain,request);assertSame(receipt,host.execute(domain,request));
            var codec=EffectCheckpointCodec.DATA;assertEquals(receipt,codec.decode(codec.encode(receipt),ResourceRoutes.Receipt.class));
            assertThrows(IllegalStateException.class,()->host.execute(domain.equals(A)?B:A,request));
            replies.add(receipt);return callback.apply(receipt);
        }
        EffectState state(String domain) {return sessions.get(domain).state().engine().domain();}
        double energy() {return host.snapshot().directory().owner(HOLDER).account("test:energy").value();}
        void move(String destination) {host.commit(host.prepareTransfer(HOLDER,destination));}
        void start(String event) {sessions.get(A).start(state(A).buffs().timeMicros(),new RuleEngine.Signal("test:"+event,new EffectEvent(HOLDER,HOLDER,ORIGIN,Set.of(),Map.of())));}
        <T> List<T> values(Class<T> type) {return replies.stream().map(ResourceRoutes.Receipt::value).filter(type::isInstance).map(type::cast).toList();}
    }
    @Test void ordinaryJsonConditionsPaymentsRefundsAndLexicalResultsFollowTheMigratedAccount() {
        var branch=new JsonObject();branch.add("if",action("result_flag","binding",new JsonPrimitive("paid")));branch.getAsJsonObject("if").addProperty("field","succeeded");
        branch.add("then",array(refund(1),refund(1)));branch.add("else",array(pool("grant_resource","requested",charge(99))));
        var r=rule("go",array(spend(.3),branch,bind(action("capture_value","value",resource()),"balance"),pool("grant_resource","requested",result("balance","value"))));r.add("if",compare(resource(),.3));
        var f=new Fixture(data(r));f.start("go");assertEquals(.8,f.energy());assertTrue(f.state(A).resources().isEmpty());
        assertEquals(List.of(.3,0.0),f.values(Resources.RefundResult.class).stream().map(v->v.grant().credited()).toList());
        assertEquals(.4,f.values(Action.CapturedValue.class).getFirst().value().value());
        assertEquals(7,f.sessions.get(B).state().engine().facts().size());
        var event=(EffectEvent)f.sessions.get(B).state().engine().facts().getFirst().signal().payload();
        assertEquals(A,event.references().get("resource_caller_domain"));assertEquals(B,event.references().get("resource_recipient_domain"));
        assertEquals(50_000_000,f.sessions.get(B).state().engine().facts().getFirst().timeMicros());
        assertFalse(f.host.drain(4).pending());
    }
    @Test void emptyPartitionsInitializeAtTheirOwnerAndResizeAndFullChargesPreserveDslReceipts() {
        var f=new Fixture(data(rule("go",array(pool("initialize_resource",null,null),spend(.75),pool("initialize_resource",null,null),
                pool("grant_full_charge","charges",number(2,"count")),pool("resize_resource","capacity",charge(.5))))),empty(0),empty(50_000_000));
        f.start("go");assertEquals(.5,f.energy());assertTrue(f.state(A).resources().isEmpty());
        assertEquals(List.of(true,false),f.values(Action.Initialized.class).stream().map(Action.Initialized::created).toList());
        assertEquals(50_000_000,f.values(Action.Initialized.class).getFirst().account().timeMicros());
        assertEquals(.75,f.values(ResourceResult.class).getFirst().credited());assertEquals(1.25,f.values(ResourceResult.class).getFirst().overflow());
        assertEquals(1.5,f.values(Resources.ResizeResult.class).getFirst().discarded());
    }
    @Test void rootAndBranchQueriesShortCircuitWithoutEvaluatingUnusedWrites() {
        var invalid=pool("grant_resource","requested",action("event_number","name",new JsonPrimitive("absent")));invalid.getAsJsonObject("requested").addProperty("unit","charge_fraction");
        var branch=new JsonObject();branch.add("if",compare(resource(),.5));branch.add("then",array(invalid));branch.add("else",array(pool("grant_resource","requested",charge(.1))));
        var r=rule("blocked",array(invalid));r.add("if",compare(resource(),.5));
        var f=new Fixture(data(r,rule("branch",array(branch))));f.start("blocked");assertEquals(.4,f.energy());assertFalse(f.sessions.get(B).inboxPending());
        f.start("branch");assertEquals(.5,f.energy());
    }
    @Test void delayedRetainedRefundUsesTheIssuerLedgerAndTheRecipientsCurrentLocation() {
        var retain=action("retain_cost","duration",number(2,"second"));retain.addProperty("cost","paid");
        var refund=action("refund_retained_cost","fraction",number(1,"multiplier"));refund.addProperty("cost","right");
        var after=new JsonObject();after.add("after",number(.1,"second"));after.addProperty("lifetime","detached");after.add("do",array(refund,refund));
        var f=new Fixture(data(rule("go",array(spend(.3),bind(retain,"right"),refund(1),after))));f.start("go");assertEquals(.1,f.energy());
        assertEquals(0,f.values(Resources.RefundResult.class).getFirst().grant().credited());f.host.drain(4);f.move(A);
        f.sessions.get(A).observe(100_000,List.of());assertEquals(.4,f.energy());
        assertEquals(.3,f.values(RetainedCosts.Refunded.class).getFirst().refund().orElseThrow().grant().credited());
        assertEquals(0,f.values(RetainedCosts.Refunded.class).getLast().refund().orElseThrow().grant().credited());
        assertTrue(f.state(B).resources().isEmpty());
    }
    @Test void recipientSelectionAndModifiersAreReadFromItsDomain() {
        var grant=action("grant_ability_energy","amount",charge(.1));grant.addProperty("slot",SLOT);grant.addProperty("value_basis","base");
        var d=data(rule("go",array(action("observe_ability_energy","slot",new JsonPrimitive(SLOT)),grant)));
        var bundle=d.getAsJsonArray("bundles").get(0).getAsJsonObject();var provider=object("{\"id\":\"test:provider\"}");provider.add("modifiers",bundle.remove("modifiers"));d.getAsJsonArray("bundles").add(provider);
        var a=initial(0).withAbilities(HOLDER,new AbilityLoadout(Map.of(SLOT,"test:current")));
        var b=empty(50_000_000).withAbilities(HOLDER,new AbilityLoadout(Map.of(SLOT,"test:old")))
                .withSource(new EffectSource("provider","test:provider",HOLDER,ORIGIN,Set.of()));
        var f=new Fixture(d,a,b);f.start("go");
        assertEquals("test:old",f.values(EnergyActions.AbilityObservation.class).getFirst().ability().orElseThrow());
        var gain=f.values(EnergyActions.AbilityResult.class).getFirst().granted();assertEquals(.14,gain.credited(),1e-12);assertEquals(.54,f.energy(),1e-12);
    }
    @Test void linkedRechargeAndNoSelectionKeepTheirQualificationBeforeContextualValues() {
        var complete=action("complete_recharge","amount",action("event_number","name",new JsonPrimitive("absent")));complete.getAsJsonObject("amount").addProperty("unit","charge_fraction");
        complete.addProperty("progress","test:progress");complete.addProperty("resource","test:uses");
        var resize=action("resize_ability_resource","capacity",complete.get("amount"));resize.addProperty("slot",SLOT);
        var grant=action("grant_ability_energy","amount",complete.get("amount"));grant.addProperty("slot",SLOT);grant.addProperty("value_basis","fixed");
        var f=new Fixture(data(rule("go",array(complete,resize,grant))));f.start("go");
        assertFalse(f.values(LinkedRecharge.Result.class).getFirst().completed());assertEquals(ResourceCapacityActions.AbilityOutcome.NO_SELECTION,f.values(ResourceCapacityActions.AbilityResult.class).getFirst().outcome());
        assertEquals(EnergyActions.AbilityOutcome.NO_SELECTION,f.values(EnergyActions.AbilityResult.class).getFirst().outcome());assertFalse(f.sessions.get(B).inboxPending());
    }
    @Test void committedRemoteMutationSurvivesUnknownCallbackAndCannotBeReplayedOrCheckpointed() {
        var f=new Fixture(data(rule("go",array(spend(.3)))));f.callback=r->{if(r.primitive().isPresent())throw new IllegalStateException("unknown after actual remote cost");return r;};
        assertThrows(IllegalStateException.class,()->f.start("go"));assertEquals(.1,f.state(B).resources().get(ENERGY).value());
        assertThrows(IllegalStateException.class,()->f.sessions.get(A).checkpoint());assertThrows(IllegalStateException.class,()->f.start("go"));
        assertEquals(1,f.values(Resources.SpendResult.class).size());
    }
    @Test void checkpointPinsRoutingModeAndRestoresOnlyUnderTheSameCompilerPolicy() {
        var f=new Fixture(data(rule("go",array(spend(.1)))));f.start("go");f.host.drain(4);var directory=f.host.snapshot().directory();
        var paid=f.values(Resources.SpendResult.class).getFirst().receipt().operation();
        for(var domain:List.of(A,B)) {
            var saved=f.sessions.get(domain).checkpoint();var encoded=EffectCheckpointCodec.encode(saved);assertEquals(2,encoded.get("format").getAsInt());
            assertEquals(saved,EffectCheckpointCodec.decode(encoded));
            assertThrows(IllegalArgumentException.class,()->EffectSession.restore(new CompiledEffects(f.program.program()),EffectCheckpointTest.clock(),1,saved,_->{throw new AssertionError();}));
            var bad=encoded.deepCopy();bad.addProperty("resource_routing","true");assertThrows(IllegalArgumentException.class,()->EffectCheckpointCodec.decode(bad));
            f.sessions.put(domain,EffectSession.restore(f.program,EffectCheckpointTest.clock(),1,EffectCheckpointCodec.decode(encoded),r->f.execute(domain,r)));
        }
        f.connect(directory);f.start("go");assertEquals(.2,f.energy());assertNotEquals(paid,f.values(Resources.SpendResult.class).getLast().receipt().operation());
        var local=new EffectSession(new CompiledEffects(f.program.program()),EffectCheckpointTest.clock(),1,empty(0),_->{throw new AssertionError();}).checkpoint();
        assertEquals(1,EffectCheckpointCodec.encode(local).get("format").getAsInt());assertFalse(EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(local)).resourceRouting());
        assertThrows(IllegalArgumentException.class,()->EffectSession.restore(f.program,EffectCheckpointTest.clock(),1,local,_->{throw new AssertionError();}));
    }
    @Test void cyclicEnergyAndSelectedCapacityAreRoutedAsOnePairWithoutBankingExtraProgress() {
        var grant=action("grant_ability_energy","amount",charge(.7));grant.addProperty("slot",SLOT);grant.addProperty("value_basis","fixed");
        var resize=action("resize_ability_resource","capacity",charge(2));resize.addProperty("slot",SLOT);
        var d=data(rule("go",array(resize,grant,grant)));
        d.getAsJsonArray("resources").get(3).getAsJsonObject().addProperty("resizable",true);
        d.getAsJsonArray("abilities").get(1).getAsJsonObject().addProperty("recharge_yield_profile","test:yield");
        d.getAsJsonArray("profiles").add(object("{\"id\":\"test:yield\",\"version\":\"resource-views\",\"input_unit\":\"charge_fraction\",\"steps\":[]}"));
        var b=empty(50_000_000).withAbilities(HOLDER,new AbilityLoadout(Map.of(SLOT,"test:current")));
        var f=new Fixture(d,initial(0),b);f.start("go");
        var resizeResult=f.values(ResourceCapacityActions.AbilityResult.class).getFirst().resized();assertEquals(2,resizeResult.after().capacity());
        var results=f.values(EnergyActions.AbilityResult.class);assertEquals(1,results.getFirst().cyclic().recharge().cycles());
        assertEquals(2,results.getFirst().cyclic().recharge().uses().after().value());assertEquals(0,results.getFirst().cyclic().recharge().progressAfter().value());
        assertEquals(.1,results.getFirst().cyclic().overflow(),1e-12);assertEquals(EnergyActions.AbilityOutcome.ALREADY_FULL,results.getLast().outcome());
        assertTrue(f.state(A).resources().isEmpty());
    }
    @Test void loopIterationsRetainIndependentPaymentsAndResolveLexicalRefunds() {
        var select=action("select_targets","radius",number(5,"meter"));
        var each=object("{\"for_each\":\"targets\",\"as\":\"target\"}");
        each.add("do",array(spend(.1),refund(.5)));
        var f=new Fixture(data(rule("go",array(bind(select,"targets"),each,action("capture_value","value",resource())))));
        f.external=request->{var query=(TargetQuery)request.command();return new TargetQuery.Result(query,TargetQuery.Outcome.AVAILABLE,
                List.of(new TargetQuery.Target("alpha",1),new TargetQuery.Target("beta",2)));};
        f.start("go");assertEquals(.3,f.energy());
        var payments=f.values(Resources.SpendResult.class);assertEquals(2,payments.size());
        assertNotEquals(payments.getFirst().receipt().operation(),payments.getLast().receipt().operation());
        assertEquals(List.of(.05,.05),f.values(Resources.RefundResult.class).stream().map(v->v.grant().credited()).toList());
        assertEquals(.3,f.values(Action.CapturedValue.class).getFirst().value().value());
    }
    @Test void completingALinkedCycleChangesBothRecipientAccountsAndPreservesAmount() {
        var meter=pool("grant_resource","requested",charge(.6));meter.addProperty("resource","test:progress");
        var complete=action("complete_recharge","amount",charge(.5));complete.addProperty("progress","test:progress");complete.addProperty("resource","test:uses");
        var f=new Fixture(data(rule("go",array(meter,complete))));f.start("go");
        var result=f.values(LinkedRecharge.Result.class).getFirst();assertTrue(result.completed());assertEquals(.5,result.charges().credited());
        assertEquals(0,f.state(B).resources().get(ResourceMutationSessionTest.PROGRESS).value());
        assertEquals(1.5,f.state(B).resources().get(ResourceMutationSessionTest.USES).value());assertTrue(f.state(A).resources().isEmpty());
    }
    @Test void aDifferentResultAfterCommitFreezesTheCallerWithoutUndoingTheActualCredit() {
        var f=new Fixture(data(rule("go",array(pool("grant_resource","requested",charge(.1))))));
        f.callback=r->r.primitive().isEmpty()?r:RuleEngine.Empty.INSTANCE;
        assertThrows(IllegalStateException.class,()->f.start("go"));assertEquals(.5,f.state(B).resources().get(ENERGY).value());
        assertThrows(IllegalStateException.class,()->f.sessions.get(A).checkpoint());assertEquals(2,f.sessions.get(B).state().engine().facts().size());
    }
    @Test void wrongCompiledSiteAndWrongModeCannotAuthorizeAnOperation() {
        var f=new Fixture(data(rule("go",array(spend(.3)))));
        assertThrows(IllegalArgumentException.class,()->new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,
                Map.of(A,new ResourceSessionAuthority.Binding(new CompiledEffects(f.program.program()),f.sessions.get(A)))));
        f.start("go");assertEquals(.1,f.energy());
        var cost=f.replies.stream().filter(ResourceRoutes.Cost.class::isInstance).map(ResourceRoutes.Cost.class::cast).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->new ResourceRoutes.Result(cost.request(),cost.primitive(),cost.value()));
        assertThrows(IllegalArgumentException.class,()->new ResourceRoutes.Cost(cost.request(),Optional.empty(),cost.value()));
    }
}
