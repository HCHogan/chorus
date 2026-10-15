package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static com.imdomestic.chorus.effect.ResourceRoutingTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.weapon.InstantReload;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;

class ActionResourceRoutingTest {
    static JsonObject scaled(JsonObject value, String from, String to, double factor) {
        var v=action("scale","of",value);v.addProperty("from",from);v.addProperty("to",to);v.addProperty("factor",factor);return v;
    }
    static JsonObject remote(String unit,double factor) {return scaled(resource(),"charge_fraction",unit,factor);}
    static JsonObject heal(JsonObject amount) {return action("heal","amount",amount);}
    static final WorldPosition POSITION=new WorldPosition("minecraft:overworld",0,0,0);
    static class Fixture extends ResourceRoutingTest.Fixture {
        final List<RuleEngine.WorldRequest> calls=new ArrayList<>();
        final List<ActionRoutes.Receipt> routed=new ArrayList<>();
        Function<ActionRoutes.Receipt,RuleEngine.ActionResult> reply=r->r;
        Consumer<RuleEngine.WorldRequest> beforeWorld=r->{};
        Consumer<RuleEngine.WorldRequest> beforeRoute=r->{};
        Fixture(JsonObject data) {super(data);external=this::world;}
        Fixture(JsonObject data,EffectState a,EffectState b) {super(data,a,b);external=this::world;}
        @Override RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request) {
            if (!(request.command() instanceof ActionRoutes.Request)) return super.execute(domain,request);
            beforeRoute.accept(request);
            var actual=(ActionRoutes.Receipt)host.execute(domain,request);assertSame(actual,host.execute(domain,request));
            assertEquals(actual,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(actual),ActionRoutes.Receipt.class));
            routed.add(actual);return reply.apply(actual);
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            calls.add(request);beforeWorld.accept(request);
            return switch(request.command()) {
                case HealingCommand h -> new HealingReceipt(request.id().toString(),h,HealingReceipt.Outcome.APPLIED,h.amount(),h.amount()/2,h.amount()/2);
                case DamageCommand d -> new DamageReceipt(request.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,d.amount()/2,Optional.empty(),false);
                case TargetQuery q -> new TargetQuery.Result(q,TargetQuery.Outcome.AVAILABLE,List.of(new TargetQuery.Target("alpha",1),new TargetQuery.Target("beta",2)));
                case PositionQuery q -> new PositionQuery.Result(q,Optional.of(POSITION));
                case DirectionQuery q -> new DirectionQuery.Result(q,Optional.of(new WorldDirection(POSITION.dimension(),1,0,0)));
                case StatusResult.Check q -> new StatusResult.Checked(q,StatusResult.Decision.ALLOWED);
                case InstantReload.Check q -> new InstantReload.Checked(q,true);
                case ProjectileFlight.Launch q -> new ProjectileFlight.Receipt(q,ProjectileFlight.Outcome.LAUNCHED,Optional.of("projectile"));
                case WorldPickup.Spawn q -> new WorldPickup.Receipt(q,WorldPickup.Outcome.SPAWNED,Optional.of("pickup"));
                case Hitscan.Query q -> new Hitscan.Result(q,Hitscan.Outcome.MISSING_POSITION,Optional.empty());
                case Hitscan.PiercingQuery q -> new Hitscan.PathResult(q,List.of(new Hitscan.PathContact(new Hitscan.Result(q.ray(),Hitscan.Outcome.MISSING_POSITION,Optional.empty()),1,true)));
                case RicochetHitscan.Query q -> new RicochetHitscan.Result(q,List.of(new RicochetHitscan.Contact(q.ray(),Hitscan.Outcome.MISSING_POSITION,Optional.empty(),1,0,0,true,0,false)));
                default -> throw new AssertionError(request.command());
            };
        }
        List<Double> healed() {return calls.stream().map(RuleEngine.WorldRequest::command).filter(HealingCommand.class::isInstance).map(HealingCommand.class::cast).map(HealingCommand::amount).toList();}
        <T> List<T> completed(Class<T> type) {return routed.stream().filter(ActionRoutes.Completed.class::isInstance).map(ActionRoutes.Completed.class::cast).map(ActionRoutes.Completed::value).filter(type::isInstance).map(type::cast).toList();}
        void balance(double value) {
            var session=sessions.get(B);var before=session.coordinationState();var account=before.engine().domain().resources().get(ENERGY);
            EffectSession.commitDomains(List.of(session.prepareInboxDomain(before,before.engine().domain().withResource(new ResourceState(ENERGY,value,account.capacity(),account.timeMicros())),List.of())));
        }
    }
    static JsonObject buffData(JsonObject... rules) {
        var d=data(rules);d.add("buffs",array(object("{\"definition\":{\"id\":\"test:buff\",\"version\":\"resource-views\",\"duration\":2,\"max_stacks\":10}}")));return d;
    }
    static JsonObject buff(String action) {var b=action(action,"duration",remote("second",2));b.addProperty("buff","test:buff");return b;}

    @Test void localBuffAndDelayedBodyReadMigratedResourcesAndKeepCapturedResultsAcrossRestore() {
        var after=object("{\"lifetime\":\"detached\"}");after.add("after",remote("second",1));after.add("do",array(heal(result("first","effective")),heal(remote("damage",10))));
        var f=new Fixture(buffData(rule("go",array(buff("grant_buff"),bind(heal(remote("damage",10)),"first"),after))));
        f.start("go");assertEquals(List.of(4.),f.healed());assertEquals(800_000,f.state(A).buffs().instances().values().iterator().next().deadline());
        assertEquals(400_000,f.state(A).timers().values().iterator().next().dueAt());assertTrue(f.state(A).resources().isEmpty());
        f.balance(.8);f.move(A);var directory=f.host.snapshot().directory();
        for(var domain:List.of(A,B)) {
            var saved=EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(f.sessions.get(domain).checkpoint()));
            f.sessions.put(domain,EffectSession.restore(f.program,EffectCheckpointTest.clock(),1,saved,r->f.execute(domain,r)));
        }
        f.connect(directory);f.sessions.get(A).observe(400_000,List.of());assertEquals(List.of(4.,2.,8.),f.healed());
        assertEquals(.8,f.energy());assertTrue(f.state(A).timers().isEmpty());
    }

    @Test void targetQueryAndLoopActionsUseRemoteAmountsAndActualLexicalResults() {
        var select=action("select_targets","radius",remote("meter",10));
        var each=object("{\"for_each\":\"targets\",\"as\":\"target\"}");var h=heal(remote("damage",10));h.add("target",object("{\"binding\":\"target\"}"));
        each.add("do",array(bind(h,"first"),heal(result("first","effective"))));
        var f=new Fixture(data(rule("go",array(bind(select,"targets"),each))));f.start("go");
        assertEquals(4,((TargetQuery)f.calls.getFirst().command()).radius());assertEquals(List.of(4.,2.,4.,2.),f.healed());
        assertEquals(5,f.calls.stream().map(RuleEngine.WorldRequest::id).distinct().count());
        var ids=f.calls.stream().filter(r->r.command() instanceof HealingCommand h2 && h2.amount()==4).map(RuleEngine.WorldRequest::id).toList();
        assertEquals(ids.getFirst().pc(),ids.getLast().pc());assertEquals(ids.getFirst().invocation()+3,ids.getLast().invocation());
    }

    @Test void preparationFreezesTheCommandWhileTheNextActionSeesNewBalances() {
        var f=new Fixture(data(rule("go",array(bind(heal(remote("damage",10)),"first"),heal(result("first","effective")),heal(remote("damage",10))))));
        f.beforeWorld=r->{if(f.calls.size()==1)f.balance(.9);};f.start("go");
        assertEquals(List.of(4.,2.,9.),f.healed());assertEquals(3,f.completed(HealingReceipt.class).size());
        for(var request:f.calls) {
            var finish=f.routed.stream().map(ActionRoutes.Receipt::request).filter(ActionRoutes.Finish.class::isInstance).map(ActionRoutes.Finish.class::cast)
                    .filter(r->r.operation().equals(request.id())).findFirst().orElseThrow();
            assertEquals(request.command(),finish.command());
        }
    }

    @Test void statusCompletionPublishesTheAuthorizedDurationAndTypedFactsOnlyOnce() {
        var f=new Fixture(buffData(rule("go",array(buff("apply_status")))));f.beforeWorld=r->f.balance(.9);f.start("go");
        assertEquals(800_000,f.state(A).buffs().instances().values().iterator().next().deadline());assertTrue(f.state(B).buffs().instances().isEmpty());
        assertEquals(1,f.calls.size());assertEquals(1,f.completed(StatusResult.class).size());assertTrue(f.completed(StatusResult.class).getFirst().applied());
    }

    @Test void nestedCalculationsAndAttackSnapshotsKeepTheResourceView() {
        var calculate=action("calculate","input",charge(0));calculate.addProperty("profile","test:second");
        var pipeline=action("calculate_pipeline","input",charge(0));pipeline.add("profiles",JsonParser.parseString("[\"test:charge\",\"test:second\"]"));
        var capture=action("capture_damage","amount",number(10,"damage"));capture.addProperty("damage_type","minecraft:generic");capture.addProperty("scaling_profile","test:damage");
        var d=data(rule("go",array(bind(calculate,"calc"),heal(scaled(result("calc","value"),"charge_fraction","damage",10)),bind(pipeline,"pipe"),heal(scaled(result("pipe","value"),"charge_fraction","damage",10)),bind(capture,"snapshot"))));
        d.getAsJsonArray("profiles").add(object("{\"id\":\"test:damage\",\"version\":\"resource-views\",\"input_unit\":\"damage\",\"steps\":[{\"type\":\"chorus:apply\",\"id\":\"add\",\"operation\":\"add\",\"group\":{\"name\":\"add\",\"reduction\":\"sum\"}}]}"));
        var modifier=object("{\"id\":\"damage\",\"profile\":\"test:damage\",\"stage\":\"add\",\"group\":\"add\",\"op\":\"add\",\"evaluate\":\"on_use\",\"stacking_key\":\"test:damage\",\"reference\":\"Synthetic migrated resource\",\"confidence\":\"assumed\"}");modifier.add("value",remote("damage",10));
        d.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("modifiers").add(modifier);
        var f=new Fixture(d);f.start("go");assertEquals(List.of(4.,8.),f.healed());
        var snapshot=f.completed(DamageSnapshot.class).getFirst();assertEquals(4,snapshot.resolve(f.state(A),ResourceReadViewTest.context(HOLDER,Map.of()).event()).getFirst().amount().value());
        f.balance(.9);assertEquals(4,snapshot.resolve(f.state(A),ResourceReadViewTest.context(HOLDER,Map.of()).event()).getFirst().amount().value());
    }

    @Test void unknownLocalPublicationKeepsTheBuffAndFactsWithoutRepeatingTheAction() {
        for(boolean wrong:List.of(false,true)) {
            var f=new Fixture(buffData(rule("go",array(buff("grant_buff"),heal(remote("damage",10))))));
            f.reply=r->{if(r instanceof ActionRoutes.Completed){if(wrong)return RuleEngine.Empty.INSTANCE;throw new IllegalStateException("unknown local publication");}return r;};
            assertThrows(IllegalStateException.class,()->f.start("go"));assertEquals(1,f.state(A).buffs().instances().size());assertTrue(f.calls.isEmpty());
            assertFalse(f.sessions.get(A).state().engine().facts().isEmpty());assertThrows(IllegalStateException.class,()->f.sessions.get(A).checkpoint());
            assertThrows(IllegalStateException.class,()->f.start("go"));assertEquals(1,f.routed.size());
        }
    }

    @Test void unknownWorldAndUnknownCompletionStopAtTheirExactPhaseWithoutRepeatingHealing() {
        for(boolean world:List.of(false,true)) {
            var f=new Fixture(data(rule("go",array(heal(remote("damage",10)),heal(number(99,"damage"))))));
            if(world)f.beforeWorld=r->{throw new IllegalStateException("unknown world healing");};
            else f.reply=r->{if(r.request() instanceof ActionRoutes.Finish)throw new IllegalStateException("unknown committed completion");return r;};
            assertThrows(IllegalStateException.class,()->f.start("go"));assertEquals(List.of(4.),f.healed());
            assertEquals(world?HealingCommand.class:ActionRoutes.Finish.class,f.sessions.get(A).state().engine().frames().getFirst().pendingCommand().orElseThrow().getClass());
            assertThrows(IllegalStateException.class,()->f.sessions.get(A).checkpoint());assertThrows(IllegalStateException.class,()->f.start("go"));assertEquals(1,f.calls.size());
        }
    }

    @Test void forgedCompletionCannotReplaceTheActualWorldResultOrPreparation() {
        var f=new Fixture(data(rule("go",array(heal(remote("damage",10))))));
        f.beforeRoute=r->{if(r.command() instanceof ActionRoutes.Finish finish) {
            var current=f.sessions.get(A).state().engine();var view=f.host.readView(A,r);
            for(var forged:List.of(new ActionRoutes.Finish(finish.site(),finish.operation(),finish.command(),RuleEngine.Empty.INSTANCE),
                    new ActionRoutes.Finish(finish.site(),finish.operation().nextInvocation(),finish.command(),finish.actual()),
                    new ActionRoutes.Finish("test:wrong",finish.operation(),finish.command(),finish.actual()))) {
                var old=current.frames().getFirst();var frame=new RuleEngine.Frame(old.id(),old.event(),old.rule(),old.definition(),old.scope(),old.pc(),old.started(),old.bindings(),old.iterations(),old.invocations(),old.retainedResults(),Optional.of(forged));
                var state=new RuleEngine.State<>(current.version(),current.domain(),current.timeMicros(),current.nextEvent(),current.nextFrame(),current.facts(),List.of(frame),current.pending(),current.receipts(),current.failure());
                assertThrows(IllegalArgumentException.class,()->f.program.evaluateActionRoute(view,state,new RuleEngine.WorldRequest(r.id(),forged)));
            }
            var noEvidence=new RuleEngine.State<>(current.version(),current.domain(),current.timeMicros(),current.nextEvent(),current.nextFrame(),current.facts(),current.frames(),current.pending(),Map.of(),current.failure());
            assertThrows(IllegalArgumentException.class,()->f.program.evaluateActionRoute(view,noEvidence,r));
        }};
        f.start("go");assertEquals(1,f.completed(HealingReceipt.class).size());assertEquals(1,f.calls.size());
    }

    @Test void retainingCostReadsRemoteDurationAndSealsTheOriginalPaymentBeforeDelayedRefund() {
        var retain=action("retain_cost","duration",remote("second",10));retain.addProperty("cost","paid");
        var refund=action("refund_retained_cost","fraction",number(1,"multiplier"));refund.addProperty("cost","right");
        var after=object("{\"lifetime\":\"detached\"}");after.add("after",remote("second",1));after.add("do",array(refund,refund));
        var f=new Fixture(data(rule("go",array(spend(.2),bind(retain,"right"),ResourceRoutingTest.refund(1),after))));f.start("go");
        assertEquals(.2,f.energy());assertEquals(2_000_000,f.completed(RetainedCosts.Handle.class).getFirst().dueAt());
        assertInstanceOf(ActionRoutes.Cost.class,f.routed.stream().filter(r->r instanceof ActionRoutes.Completed c && c.value() instanceof RetainedCosts.Handle).findFirst().orElseThrow());
        f.host.drain(4);f.sessions.get(A).observe(200_000,List.of());assertEquals(.4,f.energy());
        assertEquals(List.of(.2,0.),f.values(RetainedCosts.Refunded.class).stream().map(r->r.refund().orElseThrow().grant().credited()).toList());
    }

    static JsonArray geometry() {
        return array(bind(action("capture_position",null,null),"place"),bind(action("capture_direction",null,null),"aim"));
    }
    @Test void rayRangePenetrationsAndReflectionsUseTheSameViewForAllRayKinds() {
        for(int kind=0;kind<3;kind++) {
            var ray=object("{\"position\":\"place\",\"direction\":\"aim\"}");ray.add("range",remote("meter",10));
            var step=object("{\"as\":\"impact\"}");step.add("hitscan",ray);step.add("do",array(heal(remote("damage",10))));
            if(kind>0)step.add("pierces",remote("count",5));if(kind==2)step.add("bounces",remote("count",10));
            var steps=geometry();steps.add(step);var f=new Fixture(data(rule("go",steps)));f.start("go");
            var command=f.calls.get(2).command();var query=command instanceof Hitscan.Query q?q:command instanceof Hitscan.PiercingQuery q?q.ray():((RicochetHitscan.Query)command).ray();
            assertEquals(4,query.range());if(kind>0)assertEquals(2,command instanceof Hitscan.PiercingQuery q?q.pierces():((RicochetHitscan.Query)command).pierces());
            if(kind==2)assertEquals(4,((RicochetHitscan.Query)command).bounces());assertEquals(List.of(4.),f.healed());
        }
    }

    @Test void projectilePreparationWithAndWithoutShotMembershipPreservesParametersAndFutureBody() {
        for(boolean shot:List.of(false,true)) {
            var spec=object("{\"position\":\"place\",\"direction\":\"aim\"}");spec.add("speed",remote("meter_per_second",10));spec.add("lifetime",remote("second",2));spec.add("gravity",number(0,"meter_per_second_squared"));spec.add("drag",number(1,"multiplier"));
            var step=object("{\"as\":\"impact\"}");step.add("projectile",spec);step.add("do",array(heal(remote("damage",10))));
            var steps=geometry();
            if(shot) {
                var begin=action("begin_shot","pellets",number(1,"count"));begin.add("lifetime",number(2,"second"));steps.add(bind(begin,"group"));
                var member=object("{\"binding\":\"group\"}");member.add("pellet",number(0,"count"));step.add("shot",member);
            }
            steps.add(step);var f=new Fixture(data(rule("go",steps)));f.start("go");
            var request=f.calls.getLast();var launch=(ProjectileFlight.Launch)request.command();assertEquals(4,launch.parameters().speed());assertEquals(800_000,launch.parameters().lifetimeMicros());
            assertEquals(shot,launch.member().isPresent());
            if(!shot)assertEquals("projectile/"+request.id().frame()+"/"+request.id().pc()+"/"+request.id().invocation(),launch.continuation().id());
            f.balance(.9);
            var impact=new ProjectileFlight.Impact(ProjectileFlight.End.EXPIRED,POSITION,Optional.empty(),0,0,0,100_000);
            f.sessions.get(A).start(100_000,launch.finish(impact));assertEquals(List.of(9.),f.healed());
            assertEquals(1,f.completed(ProjectileFlight.Receipt.class).size());
        }
    }

    @Test void pickupSpawnAndClaimEligibilityReadRemoteStateButCapturedParametersStayFrozen() {
        var spec=object("{\"position\":\"place\",\"kind\":\"test:pickup\",\"recipient\":\"self\"}");spec.add("lifetime",remote("second",2));spec.add("radius",remote("meter",1));
        var step=object("{\"as\":\"contact\"}");step.add("pickup",spec);step.add("collect_if",compare(resource(),.5));step.add("do",array(heal(remote("damage",10))));
        var steps=array(bind(action("capture_position",null,null),"place"),step);var f=new Fixture(data(rule("go",steps)));f.start("go");
        var request=f.calls.getLast();var spawn=(WorldPickup.Spawn)request.command();assertEquals(.4,spawn.parameters().radius());assertEquals(800_000,spawn.parameters().lifetimeMicros());
        assertEquals("pickup/"+request.id().frame()+"/"+request.id().pc()+"/"+request.id().invocation(),spawn.continuation().id());
        assertFalse(f.program.canCollectPickup(f.host.readView(A),spawn));assertThrows(IllegalStateException.class,()->f.program.canCollectPickup(f.state(A),spawn));
        f.balance(.9);assertTrue(f.program.canCollectPickup(f.host.readView(A),spawn));assertEquals(.4,spawn.parameters().radius());
        f.sessions.get(A).start(100_000,spawn.finish(new WorldPickup.Contact(WorldPickup.End.COLLECTED,POSITION,Optional.of(HOLDER),100_000)));assertEquals(List.of(9.),f.healed());
    }

    @Test void shieldMaximumAndNewAmmoCapacityPropagateTheRemoteViewIntoTheirOwnScopes() {
        var grant=action("grant_buff","buff",new JsonPrimitive("test:shield"));var restore=action("restore_shield","amount",number(99,"damage"));restore.addProperty("buff","test:shield");
        var ammo=action("initialize_ammo","capacity",number(2,"round"));ammo.add("magazine",number(0,"round"));ammo.addProperty("capacity_profile","test:capacity");ammo.addProperty("reserves","unlimited");ammo.addProperty("weapon","self");
        var d=data(rule("go",array(grant,restore,ammo)));
        var b=object("{\"definition\":{\"id\":\"test:shield\",\"version\":\"resource-views\",\"duration\":2,\"components\":{\"numbers\":{\"capacity\":{\"initial\":0,\"unit\":\"damage\"}}}},\"shield\":{\"capacity\":\"capacity\"}}");
        b.getAsJsonObject("shield").add("maximum",remote("damage",10));d.add("buffs",array(b));
        var f=new Fixture(d);f.start("go");assertEquals(4,f.state(A).buffs().instances().values().iterator().next().components().numbers().get("capacity"));
        assertEquals(6,f.completed(AmmoActions.Observation.class).getFirst().view().orElseThrow().capacity());assertTrue(f.state(A).resources().isEmpty());
    }

    @Test void instantReloadCompletionReadsFreshRemoteCapacityAndKeepsTheActualWorldOperationIdentity() {
        var reload=action("reload_weapons","selection",new JsonPrimitive("equipped"));reload.addProperty("completion","transferred");reload.addProperty("reason","test:reload");
        var d=data(rule("go",array(bind(reload,"reload"),heal(scaled(result("reload","applied"),"round","damage",1)))));
        d.add("equipment",object("{\"slots\":[{\"id\":\"test:weapon\",\"accepts\":[\"test:weapon\"],\"weapon\":true}],\"items\":[{\"id\":\"test:weapon\",\"tags\":[\"test:weapon\"]}]}"));
        d.add("weapons",array(object("{\"item\":\"test:weapon\",\"ammunition\":{\"capacity\":2,\"magazine\":0,\"reserves\":\"unlimited\",\"capacity_profile\":\"test:capacity\"},\"reload\":{\"value\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"second\"}}}")));
        var local=ResourceReadViewTest.compile(d);var loadout=new Loadout(Map.of("test:weapon",new Loadout.Gear("weapon","test:weapon",Map.of())),Optional.of("test:weapon"));
        var initial=local.changeEquipment(initial(0).withSource(ResourceReadViewTest.source(HOLDER)),new EquipmentChange(HOLDER,Loadout.EMPTY,loadout)).state();
        var f=new Fixture(d,initial,empty(50_000_000));f.beforeWorld=r->{if(r.command() instanceof InstantReload.Check check){assertEquals(r.id(),check.operation());f.balance(.9);}};
        f.start("go");assertEquals(11,f.state(A).ammunition().get("weapon").magazine());assertEquals(List.of(11.),f.healed());assertEquals(1,f.completed(InstantReload.Result.class).size());
    }

    @Test void damageAndConsumptionEligibilityUseRemoteInputsButCompletionKeepsTheOriginalCandidates() {
        var damage=action("damage","amount",remote("damage",10));damage.addProperty("damage_type","minecraft:generic");
        var d=buffData(rule("go",array(buff("grant_buff"),bind(damage,"hit"),heal(result("hit","health_loss")),damage)));
        var consumption=object("{\"when\":\"hit\"}");consumption.add("if",compare(resource(),.3));
        d.getAsJsonArray("buffs").get(0).getAsJsonObject().add("consume_on_damage",consumption);
        var f=new Fixture(d);f.beforeWorld=r->{if(f.calls.size()==1)f.balance(.1);};f.start("go");
        var hits=f.calls.stream().map(RuleEngine.WorldRequest::command).filter(DamageCommand.class::isInstance).map(DamageCommand.class::cast).toList();
        assertEquals(List.of(4.,1.),hits.stream().map(DamageCommand::amount).toList());assertEquals(1,hits.getFirst().consumptions().size());assertTrue(hits.getLast().consumptions().isEmpty());
        assertTrue(f.state(A).buffs().instances().isEmpty());assertEquals(List.of(2.),f.healed());assertEquals(2,f.completed(DamageReceipt.class).size());
    }
}
