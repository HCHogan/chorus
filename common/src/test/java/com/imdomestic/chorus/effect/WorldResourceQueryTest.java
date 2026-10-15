package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static com.imdomestic.chorus.effect.ResourceRequestSessionTest.ORIGIN;
import static com.imdomestic.chorus.effect.ResourceRoutingTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorldResourceQueryTest {
    static final String TARGET = "victim";
    static final ResourceState.Key TARGET_ENERGY = new ResourceState.Key(TARGET, ENERGY.resource());
    static JsonObject resource(String target) { var v=ResourceRoutingTest.resource();v.addProperty("target",target);return v; }
    static JsonObject scale(JsonObject input,double factor,String unit) {
        var v=action("scale","of",input);v.addProperty("factor",factor);v.addProperty("from","charge_fraction");v.addProperty("to",unit);return v;
    }
    static JsonObject sum(JsonObject... values) {var v=action("arithmetic","of",array(values));v.addProperty("op","add");return v;}
    static JsonObject nested() {var v=action("profile_value","input",charge(0));v.addProperty("profile","test:charge");return v;}
    static JsonObject profile(String id,String unit,String op) {
        return object("{\"id\":\"test:"+id+"\",\"version\":\"resource-views\",\"input_unit\":\""+unit+"\",\"steps\":[{\"type\":\"chorus:apply\",\"id\":\"adjust\",\"operation\":\""+op+"\",\"group\":{\"name\":\"adjust\",\"reduction\":\"sum\"}}]}");
    }
    static JsonObject modifier(String id,String profile,String op,JsonObject value,String at,String provider) {
        var m=object("{\"id\":\""+id+"\",\"profile\":\"test:"+profile+"\",\"stage\":\"adjust\",\"group\":\"adjust\",\"op\":\""+op+"\",\"stacking_key\":\"test:"+id+"\",\"reference\":\"Synthetic coordinated world query\",\"confidence\":\"assumed\"}");
        m.add("value",value);m.addProperty("evaluate",at);m.addProperty("provider",provider);return m;
    }
    static JsonObject data() {
        var d=ResourceReadViewTest.data();d.addProperty("defense_profile","test:defense");d.addProperty("health_profile","test:health");
        var profiles=d.getAsJsonArray("profiles");profiles.add(profile("attack","damage","add"));profiles.add(profile("defense","damage","multiply"));
        profiles.add(profile("health","damage","add"));profiles.add(profile("layer","multiplier","add"));profiles.add(profile("native","delta","add"));
        var query=d.getAsJsonArray("bundles").get(0).getAsJsonObject();var mods=query.getAsJsonArray("modifiers");
        var mixed=modifier("mixed","attack","add",sum(scale(resource("self"),10,"damage"),scale(resource("victim"),20,"damage")),"on_use","holder");
        mixed.add("if",compare(resource("victim"),.2));mods.add(mixed);
        mods.add(modifier("live","attack","add",scale(nested(),1,"damage"),"on_hit","holder"));
        mods.add(modifier("target","attack","add",scale(resource("self"),10,"damage"),"on_hit","victim"));
        mods.add(modifier("defense","defense","multiply",scale(resource("self"),-1,"delta"),"on_hit","holder"));
        mods.add(modifier("health","health","add",scale(resource("self"),2,"damage"),"on_hit","holder"));
        mods.add(modifier("layer_frozen","layer","add",sum(scale(resource("self"),1,"multiplier"),scale(resource("victim"),1,"multiplier")),"on_use","holder"));
        mods.add(modifier("layer_live","layer","add",scale(nested(),1,"multiplier"),"on_hit","holder"));
        mods.add(modifier("native","native","add",scale(nested(),1,"delta"),"on_hit","holder"));
        var gate=object("{\"id\":\"energy\",\"action\":\"weapon_fire\"}");gate.add("if",compare(resource("self"),.5));
        var tracking=object("{\"id\":\"tracking\",\"action\":\"projectile_tracking\",\"subject\":\"victim\"}");tracking.add("if",compare(resource("self"),.4));
        query.add("action_gates",array(gate,tracking));
        var speed=object("{\"id\":\"speed\"}");speed.add("speed",scale(nested(),10,"meter_per_second"));speed.add("if",compare(resource("self"),.3));query.add("horizontal_speed_limits",array(speed));
        d.add("native_attributes",JsonParser.parseString("[{\"id\":\"test:speed\",\"attribute\":\"minecraft:movement_speed\",\"operation\":\"add_multiplied_total\",\"profile\":\"test:native\",\"input\":{\"value\":0,\"unit\":\"delta\"},\"output_unit\":\"delta\"}]"));
        var shield=object("{\"definition\":{\"id\":\"test:shield\",\"version\":\"resource-views\",\"duration\":\"permanent\",\"components\":{\"numbers\":{\"capacity\":{\"initial\":10,\"unit\":\"damage\"}}}},\"shield\":{\"capacity\":\"capacity\"}}");
        shield.getAsJsonObject("shield").add("taken_multiplier",scale(resource("self"),1,"multiplier"));d.add("buffs",array(shield));
        var remote=object("{\"id\":\"test:remote\"}");remote.add("modifiers",array(modifier("remote","attack","add",number(100,"damage"),"on_hit","holder")));d.getAsJsonArray("bundles").add(remote);
        return d;
    }
    static EffectSource victimSource() {return new EffectSource("query/victim","test:query",TARGET,new BuffInstance.Origin(TARGET,"target-source","",""),Set.of());}
    static final class Fixture extends ResourceRoutingTest.Fixture {
        Fixture() {this(data());}
        Fixture(JsonObject data) {super(data,armed(data),empty(50_000_000).withSource(new EffectSource("remote","test:remote",HOLDER,ORIGIN,Set.of())));host.claim(TARGET,A);host.commit(host.prepareTransfer(TARGET,B));}
        static EffectState armed(JsonObject data) {
            var p=ResourceReadViewTest.compile(data);var s=account(initial(0),TARGET,"energy",.25,2).withSource(victimSource());
            return s.withBuffs(Buffs.grant(s.buffs(),p.buff("test:shield"),TARGET,TARGET,ORIGIN,1,1,1_000_000).store());
        }
        ResourceReadView view() {return host.readView(A);}
        void grant(ResourceState.Key key,double amount) {host.grant(key,amount,amount);}
    }
    static DamageCommand attack() {
        return new DamageCommand(TARGET,ORIGIN,10,"minecraft:generic",Set.of(),Set.of(),false,Optional.of("test:attack"),Optional.empty(),ImpactData.EMPTY,Optional.of("test:layer"));
    }
    static EffectEvent input() {return new EffectEvent(HOLDER,TARGET,ORIGIN,Set.of(),Map.of());}
    static double outgoing(Fixture f,ResourceReadView view,DamageCommand command) {return f.program.outgoing(view,command,10).orElseThrow().output().value();}

    @Test void liveDamageStagesUseRemoteAccountsButKeepProvidersInTheCallingDomain() {
        var f=new Fixture();var view=f.view();assertTrue(view.state().resources().isEmpty());
        double out=outgoing(f,view,attack());assertEquals(21.9,out,1e-9);
        double defended=f.program.defense(view,attack(),out).orElseThrow().output().value();assertEquals(16.425,defended,1e-9);
        assertEquals(16.925,f.program.health(view,attack(),defended).orElseThrow().output().value(),1e-9);
        assertTrue(f.program.health(view,attack(),0).isEmpty());
        f.grant(ENERGY,.5);assertEquals(21.9,outgoing(f,view,attack()),1e-9);assertEquals(27.4,outgoing(f,f.view(),attack()),1e-9);
        assertSame(view.state(),f.state(A));assertEquals(50_000_000,view.account(ENERGY).timeMicros());assertFalse(f.sessions.get(B).inboxPending());
    }
    @Test void capturedSourceOperandsStayFrozenWhileVictimOperandsAndOnHitModifiersStayLive() {
        var f=new Fixture();var launch=f.view();var snapshot=f.program.captureDamage(launch.state(),attack(),Optional.of(launch));
        f.grant(ENERGY,.5);f.grant(TARGET_ENERGY,.25);var impact=f.view();
        assertEquals(29.9,outgoing(f,impact,snapshot.command(TARGET)),1e-9);
        assertEquals(34.9,outgoing(f,impact,attack()),1e-9);
        assertEquals(21.9,outgoing(f,launch,snapshot.command(TARGET)),1e-9);
        var codec=EffectCheckpointCodec.DATA;var restored=codec.decode(codec.encode(snapshot),DamageSnapshot.class);
        assertEquals(snapshot,restored);assertEquals(29.9,outgoing(f,impact,restored.command(TARGET)),1e-9);
        var removed=impact.withLocalState(impact.state().withoutSource("query/"+HOLDER));
        assertEquals(29,outgoing(f,removed,restored.command(TARGET)),1e-9);
    }
    @Test void deferredVictimConditionsUseTheHitVictimAndMissingAccountsNeverFallBack() {
        var f=new Fixture();var view=f.view();var snapshot=f.program.captureDamage(view.state(),attack(),Optional.of(view));
        // Unmanaged target has an explicit local account; its low energy suppresses the captured mixed term.
        var local=account(view.state(),"local_target","energy",.1,2);
        var localWorlds=new ResourceAuthority.Worlds(f.host.snapshot().directory(),Map.of(A,local,B,f.state(B)));
        var localView=new ResourceReadView(localWorlds,A,f.program.program());
        assertEquals(10.4,outgoing(f,localView,snapshot.command("local_target")),1e-9);
        assertThrows(IllegalArgumentException.class,()->outgoing(f,view,snapshot.command("missing")));
        assertTrue(f.state(A).resources().isEmpty());
    }
    @Test void shieldLayersAndAttackScalingShareFrozenResourcesWhileShieldWritesRemainProposals() {
        var f=new Fixture();var view=f.view();var plan=f.program.shields(view,attack(),10);
        assertEquals(2.05,plan.layers().getFirst().attackScaling().orElseThrow().output().value(),1e-9);
        assertEquals(.5125,plan.layers().getFirst().trace().multiplier(),1e-9);assertEquals(5.125,plan.layers().getFirst().trace().capacityLoss(),1e-9);
        assertEquals(10,view.state().buffs().instances().values().iterator().next().components().numbers().get("capacity"));
        var committed=view.withLocalState(plan.commit().apply(view.state()));var again=f.program.shields(committed,attack(),10);
        assertEquals(4.875,again.layers().getFirst().trace().capacityLoss(),1e-9);assertTrue(again.budget().toVanilla()>0);
        f.grant(ENERGY,.5);assertEquals(.5125,f.program.shields(view,attack(),10).layers().getFirst().trace().multiplier(),1e-9);
        assertEquals(.7625,f.program.shields(f.view(),attack(),10).layers().getFirst().trace().multiplier(),1e-9);
    }
    @Test void shieldSnapshotsRetainLaunchOperandsAndApplyCurrentVictimAndLiveAttackModifiers() {
        var f=new Fixture();var view=f.view();var snapshot=f.program.captureDamage(view.state(),attack(),Optional.of(view));
        f.grant(ENERGY,.5);f.grant(TARGET_ENERGY,.25);var impact=f.view();
        var result=f.program.shields(impact,snapshot.command(TARGET),10);
        assertEquals(2.8,result.layers().getFirst().attackScaling().orElseThrow().output().value(),1e-9);assertEquals(1.4,result.layers().getFirst().trace().multiplier(),1e-9);
        var reserved=impact.state().withoutSource("query/"+HOLDER);
        var noLive=f.program.shields(impact,snapshot.command(TARGET),10,DamageBasis.EMPTY,reserved);
        assertEquals(1.9,noLive.layers().getFirst().attackScaling().orElseThrow().output().value(),1e-9);
        assertEquals(1,noLive.layers().size());assertEquals(10,noLive.layers().getFirst().before().components().numbers().get("capacity"));
        assertEquals(.5*1.0,f.program.shields(impact,attack(),10,DamageBasis.EMPTY,reserved).layers().getFirst().trace().multiplier(),1e-9);
    }
    @Test void attackerProjectionCannotChangeBalancesTimeOrTheResourceCatalogueEvenWithoutLayers() {
        var f=new Fixture();var view=f.view();var changed=account(view.state(),"local","energy",1,2);
        assertThrows(IllegalArgumentException.class,()->f.program.shields(view,attack(),0,DamageBasis.EMPTY,changed));
        assertThrows(IllegalArgumentException.class,()->f.program.shields(view,attack(),0,DamageBasis.EMPTY,empty(1)));
        var wrong=data();wrong.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("initial",1);var other=ResourceReadViewTest.compile(wrong).withResourceRouting();
        assertThrows(IllegalArgumentException.class,()->other.shields(view,attack(),0));
        assertThrows(IllegalArgumentException.class,()->other.health(view,attack(),0));
        assertThrows(IllegalArgumentException.class,()->other.checkAction(view,ActionGate.Kind.JUMP,ActionGate.Phase.START,input()));
    }
    @Test void actorAndVictimActionGatesUseTheirOwnMigratedBalancesAtEveryPhase() {
        var f=new Fixture();var old=f.view();
        for(var phase:ActionGate.Phase.values())assertTrue(f.program.checkAction(old,ActionGate.Kind.WEAPON_FIRE,phase,input()).allowed());
        f.grant(ENERGY,.5);f.grant(TARGET_ENERGY,.25);var next=f.view();
        for(var phase:ActionGate.Phase.values()){
            var actor=f.program.checkAction(next,ActionGate.Kind.WEAPON_FIRE,phase,input());assertFalse(actor.allowed());assertEquals(HOLDER,actor.denials().getFirst().holder());
            var victim=f.program.checkAction(next,ActionGate.Kind.PROJECTILE_TRACKING,phase,input());assertFalse(victim.allowed());assertEquals(TARGET,victim.denials().getFirst().holder());
        }
        assertTrue(f.program.checkAction(old,ActionGate.Kind.PROJECTILE_TRACKING,ActionGate.Phase.START,input()).allowed());
    }
    @Test void speedAndNativeAttributesPropagateTheViewThroughNestedProfiles() {
        var f=new Fixture();var old=f.view();assertEquals(4,f.program.horizontalSpeedLimit(old,input()).maximum().orElseThrow(),1e-9);
        assertEquals(.4,f.program.nativeAttributes(old,HOLDER).getFirst().amount(),1e-9);
        f.grant(ENERGY,.5);var next=f.view();assertEquals(9,f.program.horizontalSpeedLimit(next,input()).maximum().orElseThrow(),1e-9);
        assertEquals(.9,f.program.nativeAttributes(next,HOLDER).getFirst().amount(),1e-9);assertEquals(.4,f.program.nativeAttributes(old,HOLDER).getFirst().amount(),1e-9);
        var victim=new EffectEvent(TARGET,HOLDER,victimSource().origin(),Set.of(),Map.of());assertTrue(f.program.horizontalSpeedLimit(next,victim).maximum().isEmpty());
        assertEquals(.25,f.program.nativeAttributes(next,TARGET).getFirst().amount(),1e-9);
    }
    @Test void coordinatedWorldQueriesRejectLegacyLocalEntryPointsEvenForEmptyOrZeroResults() {
        var f=new Fixture();var s=f.state(A);var v=f.view();var p=f.program;
        assertThrows(IllegalStateException.class,()->p.outgoing(s,attack(),0));assertThrows(IllegalStateException.class,()->p.defense(s,attack(),0));
        assertThrows(IllegalStateException.class,()->p.health(s,attack(),0));assertThrows(IllegalStateException.class,()->p.shields(s,attack(),0));
        assertThrows(IllegalStateException.class,()->p.nativeAttributes(s,"absent"));assertThrows(IllegalStateException.class,()->p.horizontalSpeedLimit(s,input()));
        assertThrows(IllegalStateException.class,()->p.checkAction(s,ActionGate.Kind.JUMP,ActionGate.Phase.START,input()));
        var snapshot=p.captureDamage(s,attack(),Optional.of(v));var event=ResourceReadViewTest.context(HOLDER,Map.of()).event();
        assertThrows(IllegalStateException.class,()->snapshot.resolve(s,event,p));assertThrows(IllegalStateException.class,()->snapshot.resolveShield(s,event,p));
    }
    @Test void worldQueriesRunInsideTheActualCompiledActionCallbackAndReturnItsTypedDamageResult() {
        var data=data();var query=data.getAsJsonArray("bundles").get(0).getAsJsonObject();
        var damage=action("damage","amount",number(10,"damage"));damage.addProperty("damage_type","minecraft:generic");damage.addProperty("scaling_profile","test:attack");
        var hit=rule("hit",array(bind(damage,"hit"),action("heal","amount",result("hit","health_loss"))));
        hit.add("if",object("{\"type\":\"chorus:source_is\",\"source\":\"owner\"}"));query.add("rules",array(hit));
        var f=new Fixture(data);var results=new ArrayList<Double>();var requests=new ArrayList<RuleEngine.WorldRequest>();
        f.external=r->{requests.add(r);var view=f.host.readView(A,r);
            if(r.command() instanceof DamageCommand command){
                double out=f.program.outgoing(view,command,command.amount()).orElseThrow().output().value();
                double defended=f.program.defense(view,command,out).orElseThrow().output().value();
                double hp=f.program.health(view,command,defended).orElseThrow().output().value();results.add(hp);
                return new DamageReceipt("actual",DamageReceipt.Outcome.APPLIED,0,0,hp,Optional.empty(),false);
            }
            var heal=(HealingCommand)r.command();results.add(heal.amount());return new HealingReceipt(r.id().toString(),heal,HealingReceipt.Outcome.APPLIED,heal.amount(),heal.amount(),0);
        };
        f.sessions.get(A).start(0,new RuleEngine.Signal("test:hit",input()));assertEquals(2,results.size());assertEquals(16.925,results.getFirst(),1e-9);assertEquals(results.getFirst(),results.getLast());
        assertTrue(f.sessions.get(A).state().idle());assertThrows(IllegalStateException.class,()->f.host.readView(A,requests.getFirst()));
        assertEquals(.4,f.host.snapshot().directory().owner(HOLDER).account("test:energy").value());
    }
    @Test void nestedWorldQueriesKeepDependencyCycleDetectionAndCapturedModeChecks() {
        var data=data();var query=data.getAsJsonArray("bundles").get(0).getAsJsonObject();query.getAsJsonArray("modifiers").get(0).getAsJsonObject().add("value",nested());
        var cyclic=new Fixture(data);assertTrue(assertThrows(IllegalArgumentException.class,()->cyclic.program.nativeAttributes(cyclic.view(),HOLDER)).getMessage().contains("Circular numeric profile"));
        var f=new Fixture();var view=f.view();var snapshot=f.program.captureDamage(view.state(),attack(),Optional.of(view));
        assertThrows(IllegalArgumentException.class,()->outgoing(f,view.withLocalState(view.state().withMode(EffectState.Mode.PVP)),snapshot.command(TARGET)));
    }
    @Test void pinnedSnapshotProfilesAcceptCompatibleCurrentViewsAndRejectChangedLiveLayouts() {
        var f=new Fixture();var launch=f.view();var snapshot=f.program.captureDamage(launch.state(),attack(),Optional.of(launch));
        f.grant(ENERGY,.5);f.grant(TARGET_ENERGY,.25);
        var data=JsonParser.parseString(data().toString().replace("resource-views","resource-views-next")).getAsJsonObject();
        var p=ResourceReadViewTest.compile(data).withResourceRouting();
        var local=EffectState.empty().withSource(ResourceReadViewTest.source(HOLDER)).withSource(victimSource());
        var remote=f.state(B);var directory=ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY,HOLDER,B,p,remote);
        directory=ResourceAuthority.claim(directory,TARGET,B,p,remote);
        var view=new ResourceReadView(new ResourceAuthority.Worlds(directory,Map.of(A,local,B,remote)),A,p.program());
        var result=p.outgoing(view,snapshot.command(TARGET),10).orElseThrow();assertEquals(29.9,result.output().value(),1e-9);assertEquals("resource-views",result.trace().version());
        for(var profile:data.getAsJsonArray("profiles"))if(profile.getAsJsonObject().get("id").getAsString().equals("test:attack"))
            profile.getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("group").addProperty("reduction","max");
        var incompatible=ResourceReadViewTest.compile(data).withResourceRouting();
        var otherDirectory=ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY,HOLDER,B,incompatible,remote);
        otherDirectory=ResourceAuthority.claim(otherDirectory,TARGET,B,incompatible,remote);
        var otherView=new ResourceReadView(new ResourceAuthority.Worlds(otherDirectory,Map.of(A,local,B,remote)),A,incompatible.program());
        assertTrue(assertThrows(IllegalArgumentException.class,()->incompatible.outgoing(otherView,snapshot.command(TARGET),10)).getMessage().contains("captured profile layout"));
    }
}
