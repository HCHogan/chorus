package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffObservation;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/** Actual coordinated DSL preparation, then explicit native proof, publication and interpreter Finish. */
class ShotDamageBoundaryTest {
    private static final String A="test:a",B="test:b";
    private static final EffectSource SOURCE=EffectTestSupport.source("test:shots");
    private static CompiledEffects program() throws Exception {
        var original=EffectTestSupport.json("shot_weapon");var body=new JsonArray();
        var steps=ShotGroupTest.steps(original);for(int i=0;i<5;i++)body.add(steps.get(i).deepCopy());
        body.get(3).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("pellets").addProperty("value",2);
        var data=JsonParser.parseString("""
                {"version":"shot-boundary","profiles":[{"id":"test:weapon_damage","version":"shot-boundary","input_unit":"damage",
                  "steps":[{"type":"chorus:apply","id":"damage","operation":"multiply","group":{"name":"damage","reduction":"product"}}]}],"bundles":[
                  {"id":"test:shots","rules":[{"id":"fire","on":"test:fire","do":[]}]},
                  {"id":"test:observe","rules":[{"id":"progress","on":"chorus:shot_progress",
                    "do":[{"type":"chorus:play_cue","cue":"test:progress"}]}]}]}
                """).getAsJsonObject();
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().add("do",body);
        return EffectTestSupport.compile(data).withResourceRouting();
    }
    private static final class Fixture {
        final String caller;final CompiledEffects program;
        final Map<String,EffectSession> sessions=new TreeMap<>();
        final List<ShotGroups.Progress> progress=new ArrayList<>();
        final List<ProjectileFlight.Launch> launches=new ArrayList<>();
        final ResourceSessionAuthority host;int damages;
        BiFunction<RuleEngine.WorldRequest,DamageCommand,DamageReceipt> damage=(request,command)->raw("pure");
        Fixture(String caller)throws Exception{
            this.caller=caller;program=program();
            for(String domain:List.of(A,B)){
                var observer=new EffectSource("watcher/"+domain,"test:observe","watcher/"+domain,
                        new com.imdomestic.chorus.effect.buff.BuffInstance.Origin("watcher/"+domain,"watcher/"+domain,"",""),Set.of());
                var state=EffectState.empty().withSource(observer);if(domain.equals(A))state=state.withSource(SOURCE);
                sessions.put(domain,new EffectSession(program,EffectCheckpointTest.clock(),1,state,request->world(domain,request)));
            }
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();sessions.forEach((domain,session)->bindings.put(domain,new ResourceSessionAuthority.Binding(program,session)));
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,bindings);
        }
        RuleEngine.ActionResult world(String domain,RuleEngine.WorldRequest request){
            return switch(request.command()){
                case ResourceRequests.Request ignored->host.execute(domain,request);
                case PositionQuery query->new PositionQuery.Result(query,Optional.of(new WorldPosition(caller,0,0,0)));
                case DirectionQuery query->new DirectionQuery.Result(query,Optional.of(new WorldDirection(caller,1,0,0)));
                case ProjectileFlight.Launch launch->{launches.add(launch);yield new ProjectileFlight.Receipt(launch,ProjectileFlight.Outcome.LAUNCHED,Optional.of("projectile"));}
                case DamageCommand command->{damages++;yield damage.apply(request,command);}
                case Action.CueCommand ignored->{
                    var payload=sessions.get(domain).state().engine().frames().getFirst().event().signal().payload();
                    if(payload instanceof ShotGroups.Progress p)progress.add(p);yield RuleEngine.Empty.INSTANCE;
                }
                default->throw new AssertionError(request.command());
            };
        }
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        void start(){sessions.get(A).start(0,new RuleEngine.Signal("test:fire",new EffectEvent("player","",SOURCE.origin(),Set.of(),Map.of())));}
        void hit(){sessions.get(caller).start(0,launches.getFirst().finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,
                new WorldPosition(caller,1,0,0),Optional.of("target"),0,0,0,0,1,0,1,1,true)));}
        ShotGroups.Group group(){return state(A).shotGroups().values().iterator().next();}
        void drained(){assertFalse(host.drain(100).pending());}
    }
    private static DamageReceipt raw(String id){return new DamageReceipt(id,DamageReceipt.Outcome.APPLIED,0,0,2,Optional.empty(),false);}
    private record Native(ResourceSessionAuthority.CombatBoundary ticket,ResourceSessionAuthority.NativeShot binding,
            ShotDamageAccounting.Applied shot,DamageReceipt receipt,Map<String,List<RuleEngine.Signal>> facts){}
    private static Native nativeResult(Fixture f,RuleEngine.WorldRequest request,DamageCommand command){
        var ticket=f.host.beginCombat(f.caller,Optional.of(request));
        assertThrows(IllegalArgumentException.class,()->f.host.bindCombatShot(ticket,new RuleEngine.WorldRequest(request.id(),request.command()),f.caller,"native",command));
        var binding=f.host.bindCombatShot(ticket,request,f.caller,"native",command).orElseThrow();
        assertThrows(IllegalStateException.class,()->f.host.bindCombatShot(ticket,request,f.caller,"another",command));
        var raw=raw("native").withObservedBuffs(BuffObservation.capture(ticket.worlds().domain(f.caller).buffs(),command.source().owner(),command.target()));
        assertThrows(IllegalArgumentException.class,()->f.host.settleCombatShot(ticket,binding,ticket.worlds(),f.caller,raw("wrong")));
        var shot=f.host.settleCombatShot(ticket,binding,ticket.worlds(),f.caller,raw);
        var claim=CombatDomains.capture(shot.worlds(),ticket.placement(),f.caller,raw.damageId(),command);
        var consumed=CombatDomains.finish(shot.worlds(),ticket.placement(),claim,raw);
        assertEquals(shot.worlds(),consumed.worlds());
        var facts=ShotFacts.route(ticket.placement(),shot.settled().pin().issuer(),shot.facts());
        var receipt=consumed.receipt().withConsumptionFacts(facts.getOrDefault(f.caller,List.of()));
        assertTrue(f.host.settleCombatShot(ticket,binding,shot.worlds(),f.caller,receipt).facts().isEmpty());
        return new Native(ticket,binding,shot,receipt,facts);
    }

    @Test void normalNativeCommitAndFinishPublishProgressOnceForLocalAndRemoteIssuers()throws Exception{
        for(String caller:List.of(A,B)){
            var f=new Fixture(caller);f.start();
            f.damage=(request,command)->{
                var nativeResult=nativeResult(f,request,command);
                var remote=new TreeMap<>(nativeResult.facts());remote.remove(caller);
                f.host.commitCombat(nativeResult.ticket(),nativeResult.shot().worlds(),Optional.of(nativeResult.receipt()),remote);
                assertTrue(f.group().pellets().get(0).contactOpen());
                assertEquals(Set.of("target"),f.group().pellets().get(0).effective());
                return nativeResult.receipt();
            };
            f.hit();f.drained();assertEquals(1,f.damages);assertEquals(1,f.progress.size());
            assertFalse(f.group().pellets().get(0).contactOpen());assertDoesNotThrow(f.host::checkpoint);
        }
    }

    @Test void missingTargetWithoutNativeBoundarySettlesAndClosesWithoutProgress()throws Exception{
        var f=new Fixture(B);f.start();f.damage=(request,command)->new DamageReceipt("missing",DamageReceipt.Outcome.FAILED,0,0,0,Optional.empty(),false);
        f.hit();f.drained();assertEquals(1,f.damages);assertTrue(f.progress.isEmpty());
        var pellet=f.group().pellets().get(0);assertTrue(pellet.hits().isEmpty());assertFalse(pellet.contactOpen());assertDoesNotThrow(f.host::checkpoint);
    }

    @Test void unknownEnclosingResultRetainsExactRemoteShotWithoutClosingOrReplay()throws Exception{
        var f=new Fixture(B);f.start();var operation=new EffectSession.WorldOperation[1];
        f.damage=(request,command)->{
            operation[0]=f.sessions.get(B).operation(request);var nativeResult=nativeResult(f,request,command);
            var facts=new TreeMap<String,List<RuleEngine.Signal>>(nativeResult.facts());
            var local=new ArrayList<>(DamageFacts.from(command,nativeResult.receipt(),nativeResult.binding().attribution()));
            local.addAll(facts.getOrDefault(B,List.of()));facts.put(B,local);
            f.host.retainCombat(nativeResult.ticket(),nativeResult.shot().worlds(),facts);
            assertTrue(operation[0].committed().isEmpty());throw new IllegalStateException("unknown after confirmed HP loss");
        };
        assertThrows(IllegalStateException.class,f::hit);assertEquals(1,f.damages);
        assertEquals(Set.of("target"),f.group().pellets().get(0).effective());assertTrue(f.group().pellets().get(0).contactOpen());
        assertEquals(Optional.of(operation[0].request().id()),f.sessions.get(B).state().engine().pending());
        assertThrows(IllegalStateException.class,f.host::checkpoint);assertThrows(IllegalStateException.class,f::hit);assertEquals(1,f.damages);
        var hits=f.sessions.get(B).state().engine().facts().stream().filter(e->e.signal().type().equals("chorus:hit")).toList();
        assertEquals(1,hits.size());assertEquals(f.group().handle().reference(),((EffectEvent)hits.getFirst().signal().payload()).references().get("shot"));
        assertEquals(1,f.sessions.get(A).state().engine().facts().stream().filter(e->e.signal().type().equals(ShotGroups.PROGRESS)).count());
        assertThrows(IllegalStateException.class,()->f.host.drain(4));assertTrue(f.progress.isEmpty());assertEquals(1,f.damages);
    }

    @Test void fabricatedNativeLedgerChangesCannotPublishAlongsideARealReceipt()throws Exception{
        var f=new Fixture(B);f.start();
        f.damage=(request,command)->{
            var nativeResult=nativeResult(f,request,command);var worlds=nativeResult.shot().worlds();
            var beforeA=f.state(A);var beforeB=f.state(B);var group=worlds.domain(A).shotGroups().values().iterator().next();
            assertThrows(IllegalArgumentException.class,()->f.host.commitCombat(nativeResult.ticket(),worlds,Optional.of(raw("unrelated")),nativeResult.facts()));
            var extra=worlds.domain(A).withShotGroup(new ShotGroups.Group(group.handle(),Map.of(
                    0,group.pellets().get(0),1,new ShotGroups.Pellet(ShotGroups.Phase.FLYING,1,true,Set.of("target"),Set.of("target")))));
            var domains=new TreeMap<>(worlds.domains());domains.put(A,extra);
            var forged=new ResourceAuthority.Worlds(worlds.directory(),domains,worlds.timelines());
            assertThrows(IllegalArgumentException.class,()->f.host.commitCombat(nativeResult.ticket(),forged,Optional.of(nativeResult.receipt()),nativeResult.facts()));
            assertThrows(IllegalArgumentException.class,()->f.host.retainCombat(nativeResult.ticket(),forged,nativeResult.facts()));
            assertSame(beforeA,f.state(A));assertSame(beforeB,f.state(B));
            f.host.commitCombat(nativeResult.ticket(),worlds,Optional.of(nativeResult.receipt()),nativeResult.facts());return nativeResult.receipt();
        };
        f.hit();f.drained();assertEquals(1,f.progress.size());assertDoesNotThrow(f.host::checkpoint);
    }
}
