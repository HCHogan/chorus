package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/** Explicit coordinated hosts drive the real native projections; the default runtime is not replaced. */
public class ResourceProjectionGameTest {
    static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    static JsonObject constant(double value,String unit) { return object("{\"type\":\"chorus:constant\",\"value\":"+value+",\"unit\":\""+unit+"\"}"); }
    static JsonObject energy() { return object("{\"type\":\"chorus:profile_value\",\"profile\":\"test:energy\",\"input\":{\"type\":\"chorus:constant\",\"value\":0,\"unit\":\"charge_fraction\"}}"); }
    static JsonObject scale(double factor,String unit) {
        var v=object("{\"type\":\"chorus:scale\",\"factor\":"+factor+",\"from\":\"charge_fraction\",\"to\":\""+unit+"\"}");v.add("of",energy());return v;
    }
    static JsonObject modifier(String id,JsonObject value) {
        var m=object("{\"id\":\""+id+"\",\"profile\":\"test:"+id+"\",\"stage\":\"add\",\"group\":\"add\",\"op\":\"add\",\"stacking_key\":\"test:"+id+"\",\"reference\":\"Synthetic native resource projection\",\"confidence\":\"assumed\"}");m.add("value",value);return m;
    }
    static JsonObject data() {
        var data=object("""
            {"version":"projection-views","resources":[{"id":"test:energy","capacity":1,"initial":0.25}],
             "native_attributes":[
               {"id":"test:speed","attribute":"minecraft:movement_speed","operation":"add_multiplied_total","profile":"test:speed","input":{"value":0,"unit":"delta"},"output_unit":"delta"},
               {"id":"test:health","attribute":"minecraft:max_health","operation":"add_value","profile":"test:health","input":{"value":0,"unit":"damage"},"output_unit":"damage"}],
             "bundles":[{"id":"test:query","rules":[{"id":"heal","on":"test:heal","do":[
               {"type":"chorus:grant_resource","resource":"test:energy","requested":{"type":"chorus:constant","value":0.5,"unit":"charge_fraction"}},
               {"type":"chorus:heal","amount":{"type":"chorus:constant","value":200,"unit":"damage"}}]}]},
               {"id":"test:remote"}]}
            """);
        var profiles=new JsonArray();
        for(var entry:Map.of("energy","charge_fraction","speed","delta","health","damage").entrySet())
            profiles.add(object("{\"id\":\"test:"+entry.getKey()+"\",\"version\":\"projection-views\",\"input_unit\":\""+entry.getValue()+"\",\"steps\":[{\"type\":\"chorus:apply\",\"id\":\"add\",\"operation\":\"add\",\"group\":{\"name\":\"add\",\"reduction\":\"sum\"}}]}"));
        data.add("profiles",profiles);
        var query=data.getAsJsonArray("bundles").get(0).getAsJsonObject();var modifiers=new JsonArray();
        modifiers.add(modifier("energy",object("{\"type\":\"chorus:resource\",\"resource\":\"test:energy\"}")));
        modifiers.add(modifier("speed",scale(1,"delta")));modifiers.add(modifier("health",scale(40,"damage")));query.add("modifiers",modifiers);
        var gates=new JsonArray();
        for(var kind:MinecraftMovementInput.KINDS){
            var gate=object("{\"id\":\""+kind.name().toLowerCase(Locale.ROOT)+"\",\"action\":\""+kind.name().toLowerCase(Locale.ROOT)+"\"}");
            var condition=object("{\"type\":\"chorus:compare\",\"op\":\"gt\"}");condition.add("left",energy());condition.add("right",constant(.5,"charge_fraction"));gate.add("if",condition);gates.add(gate);
        }
        query.add("action_gates",gates);var limits=new JsonArray();var limit=object("{\"id\":\"speed\"}");limit.add("speed",scale(10,"meter_per_second"));limits.add(limit);query.add("horizontal_speed_limits",limits);
        var remote=new JsonArray();remote.add(modifier("health",constant(100,"damage")));data.getAsJsonArray("bundles").get(1).getAsJsonObject().add("modifiers",remote);
        return data;
    }
    static CompiledEffects compile(JsonObject data) { return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,data).getOrThrow().withResourceRouting(); }
    static EffectClock clock() { return new EffectClock((_,_)->new EffectClock.Rate(0,List.of())); }
    static EffectSource source(String bundle,LivingEntity actor) {
        String id=actor.getUUID().toString();return new EffectSource(bundle+"/"+id,"test:"+bundle,id,new BuffInstance.Origin(id,bundle,"",""),Set.of());
    }
    static void refused(GameTestHelper h,Class<? extends RuntimeException> kind,Runnable operation) {
        try { operation.run(); } catch(RuntimeException error) { if(kind.isInstance(error))return;throw error; }
        h.assertTrue(false,"Expected "+kind.getSimpleName());
    }
    static final class Fixture implements AutoCloseable {
        static final String A="test:physical",B="test:resources";
        final GameTestHelper helper;final LivingEntity actor;final CompiledEffects program=compile(data());
        final MinecraftAttributeProjection attributes;final MinecraftMovementProjection movement;final MinecraftHorizontalSpeedProjection speed;
        final Map<String,EffectSession> sessions=new TreeMap<>();final ResourceSessionAuthority host;
        boolean loseReceipt;int heals;
        Fixture(GameTestHelper h) { this(h,h.spawnWithNoFreeWill(EntityTypes.COW,2,3,2)); }
        Fixture(GameTestHelper h,LivingEntity actor) {
            helper=h;this.actor=actor;actor.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);actor.setHealth(2);
            var local=EffectState.empty().withSource(source("query",actor)).withResource(new ResourceState(key(),.25,1,0));
            var remote=EffectState.empty().withSource(source("remote",actor));
            Map.of(A,local,B,remote).forEach((domain,state)->sessions.put(domain,new EffectSession(program,clock(),1,state,r->world(domain,r))));
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();sessions.forEach((domain,s)->bindings.put(domain,new ResourceSessionAuthority.Binding(program,s)));
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,bindings);host.claim(actor.getUUID().toString(),A);host.commit(host.prepareTransfer(actor.getUUID().toString(),B));
            attributes=new MinecraftAttributeProjection(h.getLevel(),program);movement=new MinecraftMovementProjection(h.getLevel(),program);speed=new MinecraftHorizontalSpeedProjection(h.getLevel(),program);
        }
        ResourceState.Key key() { return new ResourceState.Key(actor.getUUID().toString(),"test:energy"); }
        ResourceReadView view() { return host.readView(A); }
        void grant(double amount) { host.grant(key(),amount,amount); }
        void reconcile(ResourceReadView view) { attributes.reconcile(view);movement.reconcile(view);speed.reconcile(view); }
        RuleEngine.ActionResult world(String domain,RuleEngine.WorldRequest request) {
            if(request.command() instanceof ResourceRequests.Request)return host.execute(domain,request);
            var view=host.readView(domain,request);reconcile(view);
            var receipt=MinecraftHealingExecutor.execute(request.id().toString(),actor,(HealingCommand)request.command());heals++;
            if(loseReceipt)throw new IllegalStateException("Injected lost healing receipt after native projections");return receipt;
        }
        public void close() { try { attributes.close(); } finally { try { movement.close(); } finally { speed.close();actor.discard(); } } }
    }
    @GameCase(environment="chorus_gametest:resource_projections")
    public void nativeAttributesUseFrozenRemoteBalancesAndPreserveExternalModifiers(GameTestHelper h) {
        try(var f=new Fixture(h)){
            var attribute=f.actor.getAttribute(Attributes.MOVEMENT_SPEED);double base=attribute.getBaseValue();
            var external=new AttributeModifier(Identifier.parse("test:external"),.1,AttributeModifier.Operation.ADD_VALUE);attribute.addPermanentModifier(external);
            var old=f.view();f.attributes.reconcile(old);near(h,attribute.getValue(),(base+.1)*1.25,"remote nested speed contribution");near(h,f.actor.getMaxHealth(),30,"foreign domain provider not imported");
            f.grant(.5);f.attributes.reconcile(old);near(h,f.actor.getMaxHealth(),30,"captured resources changed after grant");
            f.attributes.reconcile(f.view());near(h,f.actor.getMaxHealth(),50,"current resources not projected");near(h,attribute.getValue(),(base+.1)*1.75,"current speed contribution");
            var owned=NativeAttributeGameTest.projections(attribute);f.attributes.reconcile(f.view());h.assertTrue(attribute.getModifier(owned.getFirst().id())==owned.getFirst(),"unchanged view rewrote modifier");
            h.assertTrue(f.view().state().resources().isEmpty(),"projection recreated migrated resource");near(h,f.actor.getHealth(),2,"projection healed target");
            f.attributes.close();near(h,attribute.getValue(),base+.1,"close changed foreign attribute");h.assertTrue(attribute.hasModifier(external.id()),"foreign modifier removed");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_projections")
    public void remoteGatesFilterRealPlayerInputAndConstrainPhysicalAxes(GameTestHelper h) {
        try(var f=new Fixture(h,NativeMeleeGameTest.player(h))){
            var player=(net.minecraft.server.level.ServerPlayer)f.actor;var input=new Input(true,false,true,false,true,true,true);var old=f.view();
            player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(input));f.movement.reconcile(old);h.assertValueEqual(player.getLastClientInput(),input,"low balance restricted input");
            f.grant(.5);f.movement.reconcile(f.view());h.assertValueEqual(NativeMovementGameTest.mask(player),15,"remote conditions did not constrain all axes and inputs");h.assertValueEqual(player.getLastClientInput(),Input.EMPTY,"held input not filtered");
            player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(input));h.assertValueEqual(player.getLastClientInput(),Input.EMPTY,"new packet bypassed restrictions");
            var position=player.position();player.setDeltaMovement(1,1,1);player.setPos(position.add(1,1,1));h.assertValueEqual(player.getDeltaMovement(),Vec3.ZERO,"velocity ignored axis constraints");h.assertValueEqual(player.position(),position,"position ignored axis anchors");
            h.assertTrue(f.movement.reports().stream().allMatch(r->r.query().outcome()==MinecraftNativeActions.Outcome.RESTRICTED),"query report lost denials");
            f.movement.reconcile(old);h.assertValueEqual(NativeMovementGameTest.mask(player),0,"frozen earlier gates were not used");player.jumpFromGround();h.assertTrue(player.getDeltaMovement().y>0,"jump not released");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_projections")
    public void remoteSpeedChangesKeepOnePhysicalTravelBudgetAndLeaveVerticalMovementFree(GameTestHelper h) {
        try(var f=new Fixture(h)){
            var old=f.view();f.speed.reconcile(old);near(h,MinecraftHorizontalSpeed.speed(f.actor).orElseThrow(),2.5,"remote speed ceiling");
            var start=f.actor.position();f.actor.setDeltaMovement(3,.2,4);near(h,Math.hypot(f.actor.getDeltaMovement().x,f.actor.getDeltaMovement().z),.125,"native horizontal velocity");near(h,f.actor.getDeltaMovement().y,.2,"vertical velocity changed");
            f.actor.setPos(start.add(1,.3,0));near(h,f.actor.getX()-start.x,.125,"initial movement budget");
            f.grant(.25);f.speed.reconcile(f.view());f.actor.setPos(f.actor.position().add(1,.3,0));near(h,f.actor.getX()-start.x,.25,"ceiling change replenished already used budget");
            f.speed.reconcile(old);f.actor.setPos(f.actor.position().add(1,.3,0));near(h,f.actor.getX()-start.x,.25,"older view granted extra travel");near(h,f.actor.getY()-start.y,.9,"vertical movement capped");
            f.speed.close();f.actor.setPos(f.actor.position().add(1,0,0));near(h,f.actor.getX()-start.x,1.25,"close retained cap");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_projections")
    public void missingAccountsPreserveAtomicAttributesAndFailClosedForEachMovementRecipient(GameTestHelper h) {
        try(var f=new Fixture(h)){
            f.reconcile(f.view());var before=f.attributes.reports();f.grant(.5);var broken=h.spawnWithNoFreeWill(EntityTypes.COW,4,3,2);
            try{
                var view=f.view();var invalid=view.withLocalState(view.state().withSource(source("query",broken)));
                refused(h,IllegalArgumentException.class,()->f.attributes.reconcile(invalid));near(h,f.actor.getMaxHealth(),30,"earlier attribute query partly applied");h.assertValueEqual(f.attributes.reports(),before,"failed attribute query replaced successful report");
                refused(h,IllegalArgumentException.class,()->f.movement.reconcile(invalid));h.assertValueEqual(NativeMovementGameTest.mask(broken),15,"missing account authorized movement");h.assertValueEqual(NativeMovementGameTest.mask(f.actor),15,"healthy recipient lost own decision");
                refused(h,IllegalArgumentException.class,()->f.speed.reconcile(invalid));near(h,MinecraftHorizontalSpeed.speed(broken).orElseThrow(),0,"missing account authorized travel");near(h,MinecraftHorizontalSpeed.speed(f.actor).orElseThrow(),7.5,"healthy recipient lost own speed");
                h.assertTrue(f.movement.reports().stream().anyMatch(r->r.query().outcome()==MinecraftNativeActions.Outcome.QUERY_FAILED&&r.query().decision().isEmpty()),"failure invented normal gate decision");
                h.assertTrue(f.speed.reports().stream().anyMatch(r->r.error().isPresent()&&r.decision().isEmpty()),"missing speed error report");
                f.reconcile(f.view());h.assertValueEqual(NativeMovementGameTest.mask(broken),0,"removed broken source retained restriction");h.assertTrue(MinecraftHorizontalSpeed.speed(broken).isEmpty(),"removed broken source retained ceiling");
            }finally{broken.discard();}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_projections")
    public void missingOrWrongViewsAreRejectedBeforeCleanupIncludingEmptyProjections(GameTestHelper h) {
        try(var f=new Fixture(h)){
            f.grant(.5);var view=f.view();f.reconcile(view);
            refused(h,IllegalStateException.class,()->f.attributes.reconcile(view.state()));refused(h,IllegalStateException.class,()->f.movement.reconcile(view.state()));refused(h,IllegalStateException.class,()->f.speed.reconcile(view.state()));
            refused(h,IllegalStateException.class,()->f.attributes.beforeLoadedHealth(view.state(),f.actor,2));
            var otherData=data();otherData.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("initial",.5);var other=compile(otherData);
            var wrong=new ResourceReadView(new ResourceAuthority.Worlds(ResourceAuthority.Directory.EMPTY,Map.of(Fixture.A,EffectState.empty())),Fixture.A,other.program());
            refused(h,IllegalArgumentException.class,()->f.attributes.reconcile(wrong));refused(h,IllegalArgumentException.class,()->f.movement.reconcile(wrong));refused(h,IllegalArgumentException.class,()->f.speed.reconcile(wrong));refused(h,IllegalArgumentException.class,()->f.attributes.beforeLoadedHealth(wrong,f.actor,2));
            near(h,f.actor.getMaxHealth(),50,"invalid view removed attributes");h.assertValueEqual(NativeMovementGameTest.mask(f.actor),15,"invalid view released movement");near(h,MinecraftHorizontalSpeed.speed(f.actor).orElseThrow(),7.5,"invalid view released speed");
            var empty=compile(object("{\"version\":\"empty\"}"));
            try(var attrs=new MinecraftAttributeProjection(h.getLevel(),empty);var move=new MinecraftMovementProjection(h.getLevel(),empty);var speed=new MinecraftHorizontalSpeedProjection(h.getLevel(),empty)){
                refused(h,IllegalStateException.class,()->attrs.reconcile(EffectState.empty()));refused(h,IllegalStateException.class,()->move.reconcile(EffectState.empty()));refused(h,IllegalStateException.class,()->speed.reconcile(EffectState.empty()));
                refused(h,IllegalArgumentException.class,()->attrs.reconcile(view));refused(h,IllegalArgumentException.class,()->move.reconcile(view));refused(h,IllegalArgumentException.class,()->speed.reconcile(view));
            }
            var removed=view.withLocalState(view.state().withoutSource(source("query",f.actor).instance()));f.reconcile(removed);near(h,f.actor.getMaxHealth(),20,"valid empty holder set did not clean attributes");h.assertValueEqual(NativeMovementGameTest.mask(f.actor),0,"valid empty holder set did not clean movement");h.assertTrue(MinecraftHorizontalSpeed.speed(f.actor).isEmpty(),"valid empty holder set did not clean speed");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_projections")
    public void explicitLoadHookProjectsAnUnindexedReplacementUsingSavedLiveness(GameTestHelper h) {
        try(var f=new Fixture(h)){
            f.attributes.reconcile(f.view());var replacement=Objects.requireNonNull(EntityTypes.COW.create(h.getLevel(),EntitySpawnReason.LOAD));replacement.setUUID(f.actor.getUUID());replacement.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);replacement.setHealth(0);
            try{
                var state=f.sessions.get(Fixture.A).state();f.attributes.beforeLoadedHealth(f.view(),replacement,30);near(h,replacement.getMaxHealth(),30,"unindexed replacement lacked remote capacity");near(h,f.actor.getMaxHealth(),20,"old entity projection not removed");
                replacement.setHealth(30);near(h,replacement.getHealth(),30,"saved health was clamped before explicit projection hook");
                f.attributes.beforeLoadedHealth(f.view(),replacement,30);h.assertValueEqual(NativeAttributeGameTest.projections(replacement.getAttribute(Attributes.MAX_HEALTH)).size(),1,"replacement projection stacked");h.assertTrue(h.getLevel().getEntity(replacement.getUUID())!=replacement,"replacement unexpectedly indexed");
                h.assertTrue(f.sessions.get(Fixture.A).state()==state,"load projection advanced rules or clock");
                f.attributes.beforeLoadedHealth(f.view(),replacement,0);near(h,replacement.getMaxHealth(),20,"saved dead entity retained projection");
            }finally{replacement.discard();}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_projections")
    public void activeWorldCallbackProjectsCommittedRemoteEnergyBeforeHealingAndRetainsUnknownWrites(GameTestHelper h) {
        try(var f=new Fixture(h)){
            f.loseReceipt=true;var source=source("query",f.actor);var signal=new RuleEngine.Signal("test:heal",new EffectEvent(source.holder(),source.holder(),source.origin(),Set.of(),Map.of()));
            refused(h,IllegalStateException.class,()->f.sessions.get(Fixture.A).start(0,signal));
            near(h,f.actor.getMaxHealth(),50,"world callback used old resource state");near(h,f.actor.getHealth(),50,"real heal did not see projected maximum");h.assertValueEqual(NativeMovementGameTest.mask(f.actor),15,"committed movement restrictions lost");near(h,MinecraftHorizontalSpeed.speed(f.actor).orElseThrow(),7.5,"committed ceiling lost");
            refused(h,IllegalStateException.class,f.host::checkpoint);refused(h,IllegalStateException.class,()->f.sessions.get(Fixture.A).start(0,signal));h.assertValueEqual(f.heals,1,"unknown world heal replayed");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_projections")
    public void resourceHandoffAndJointRestoreRebuildProjectionsWithoutCopyingForeignProviders(GameTestHelper h) {
        try(var f=new Fixture(h)){
            f.grant(.5);f.reconcile(f.view());f.host.commit(f.host.prepareTransfer(f.actor.getUUID().toString(),Fixture.A));var saved=f.host.checkpoint();
            var policies=new TreeMap<String,ResourceSessionAuthority.RestoreBinding>();for(String domain:f.sessions.keySet())policies.put(domain,new ResourceSessionAuthority.RestoreBinding(f.program,clock(),1,_->{throw new AssertionError("Restore projected a world action");}));
            var restored=ResourceSessionAuthority.restore(saved,policies);f.attributes.close();f.movement.close();f.speed.close();
            var view=restored.authority().readView(Fixture.A);h.assertValueEqual(view.owner(f.key().holder()).domain(),Fixture.A,"restored owner domain");f.reconcile(view);
            near(h,f.actor.getMaxHealth(),50,"restored native contribution");near(h,f.actor.getHealth(),2,"restore replayed healing");near(h,MinecraftHorizontalSpeed.speed(f.actor).orElseThrow(),7.5,"restored speed ceiling");h.assertValueEqual(NativeMovementGameTest.mask(f.actor),15,"restored gate restrictions");
            h.assertTrue(restored.bindings().get(Fixture.B).session().state().engine().domain().resources().isEmpty(),"handoff left duplicate account");
        }h.succeed();
    }
}
