package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.level.GameType;

public class NativeAttributeGameTest {
    static ProjectileGameTest.Harness harness(GameTestHelper h)throws Exception{
        return new ProjectileGameTest.Harness(h,"native_attributes",data->{for(String id:List.of("test:projectile","test:power")){var bundle=new JsonObject();bundle.addProperty("id",id);data.getAsJsonArray("bundles").add(bundle);}},true);
    }
    static String id(LivingEntity e){return e.getUUID().toString();}
    static EffectSource source(String name,String bundle,LivingEntity e){return new EffectSource(name,"test:"+bundle,id(e),new BuffInstance.Origin(id(e),name,"",""),Set.of());}
    static void bind(ProjectileGameTest.Harness t,String name,String bundle,LivingEntity e){t.runtime.bind(source(name,bundle,e));}
    static void signal(ProjectileGameTest.Harness t,String type,LivingEntity target){t.runtime.start(new RuleEngine.Signal("test:"+type,new EffectEvent(id(t.owner),id(target),new BuffInstance.Origin(id(t.owner),"cast","",""),Set.of(),Map.of())));}
    static List<AttributeModifier> projections(AttributeInstance attribute){return attribute.getModifiers().stream().filter(m->m.id().getNamespace().equals("chorus")&&m.id().getPath().startsWith("projection/")).toList();}
    static void settled(GameTestHelper h,ProjectileGameTest.Harness t){h.assertTrue(t.runtime.failure().isEmpty()&&t.runtime.state().idle(),"attribute runtime failure: "+t.runtime.failure());}
    @GameCase(environment="chorus_gametest:native_attrs_lifecycle")
    public void nativeContributionsPreserveBaseValuesOtherModsAndTheirOwnOperationOrder(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            var attribute=t.owner.getAttribute(Attributes.MOVEMENT_SPEED);double base=attribute.getBaseValue();
            var flat=new AttributeModifier(Identifier.parse("test:external_flat"),.05,AttributeModifier.Operation.ADD_VALUE);
            var total=new AttributeModifier(Identifier.parse("test:external_total"),.2,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            attribute.addPermanentModifier(flat);attribute.addTransientModifier(total);
            bind(t,"fast","fast",t.owner);bind(t,"base","base",t.owner);settled(h,t);
            near(h,attribute.getValue(),(base+.05)*1.25*1.2*1.5,"flat then base multiplier then total factors");near(h,attribute.getBaseValue(),base,"native base preserved");
            h.assertValueEqual(projections(attribute).size(),2,"one Chorus contribution for each native operation");
            var original=projections(attribute);for(int i=0;i<3;i++)t.runtime.prepare();
            for(var modifier:original){h.assertTrue(attribute.getModifier(modifier.id())==modifier,"unchanged projection dirtied native modifier");h.assertTrue(!attribute.getPermanentModifiers().contains(modifier),"projection became permanent");}
            attribute.setBaseValue(.2);near(h,attribute.getValue(),(.2+.05)*1.25*1.2*1.5,"other systems can still change native base");
            t.runtime.unbind("fast");near(h,attribute.getValue(),(.2+.05)*1.25*1.2,"only total Chorus contribution removed");
            t.runtime.close();h.assertTrue(projections(attribute).isEmpty(),"close retained native projection");near(h,attribute.getValue(),(.2+.05)*1.2,"foreign modifiers survive close");
            h.assertTrue(attribute.hasModifier(flat.id())&&attribute.hasModifier(total.id()),"foreign modifier removed");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:native_attrs_expiry",maxTicks=14)
    public void recipientBuffProjectsMovementAndGravityUntilItsActualExpiry(GameTestHelper h)throws Exception{
        var t=harness(h);try{
            var target=t.cow(2.5,43,3.5);var speed=target.getAttribute(Attributes.MOVEMENT_SPEED);var gravity=target.getAttribute(Attributes.GRAVITY);
            double originalSpeed=speed.getValue(),originalGravity=gravity.getValue();bind(t,"inputs","inputs",t.owner);bind(t,"fast","fast",t.owner);signal(t,"slow",target);settled(h,t);
            near(h,speed.getValue(),originalSpeed*.75,"recipient slowdown");near(h,gravity.getValue(),originalGravity*.5,"recipient gravity reduction");
            h.assertTrue(projections(t.owner.getAttribute(Attributes.GRAVITY)).isEmpty(),"recipient buff changed provider gravity");
            h.runAfterDelay(6,()->{try(t){t.runtime.prepare();settled(h,t);near(h,speed.getValue(),originalSpeed,"expired speed projection removed");near(h,gravity.getValue(),originalGravity,"expired gravity projection removed");
                h.assertTrue(projections(speed).isEmpty()&&projections(gravity).isEmpty(),"target with no remaining sources retained projection");h.assertValueEqual(projections(t.owner.getAttribute(Attributes.MOVEMENT_SPEED)).size(),1,"provider source remains");h.succeed();}});
        }catch(Exception|Error failure){t.close();throw failure;}
    }
    @GameCase(environment="chorus_gametest:native_attrs_world_boundary")
    public void aFollowingWorldActionSeesNewAttributesAndUnknownResultsRetainThemUntilClose(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            var attribute=t.owner.getAttribute(Attributes.MAX_HEALTH);attribute.setBaseValue(20);t.owner.setHealth(19);bind(t,"inputs","inputs",t.owner);
            t.failAfterHealing=true;boolean failed=false;try{signal(t,"health",t.owner);}catch(IllegalStateException expected){failed=true;}
            h.assertTrue(failed&&t.runtime.failure().isPresent(),"unknown heal not retained");near(h,attribute.getValue(),30,"committed projection retained");near(h,t.owner.getHealth(),30,"heal used newly projected capacity");
            h.assertTrue(t.runtime.state().engine().domain().buffs().instances().values().stream().anyMatch(b->b.definition().id().equals("test:health")),"committed buff lost");
            t.runtime.close();near(h,attribute.getValue(),20,"explicit close removes own native projection");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:native_attrs_atomic")
    public void laterCalculationFailureCannotPartlyReplaceEarlierNativeAttributes(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            var attribute=t.owner.getAttribute(Attributes.MOVEMENT_SPEED);double base=attribute.getValue();bind(t,"fast","fast",t.owner);
            var before=t.runtime.state().engine().domain().sources();var after=new HashMap<>(before);after.put("fast",source("fast","faster",t.owner));after.put("bad",source("bad","bad",t.owner));
            boolean failed=false;try{t.runtime.replaceSources(SourceBatch.between(before,after));}catch(RuntimeException expected){failed=true;}
            h.assertTrue(failed&&t.runtime.failure().isPresent(),"bad projection did not fail");near(h,attribute.getValue(),base*1.5,"earlier calculation was not partly written");
            h.assertValueEqual(t.runtime.state().engine().domain().sources().get("fast").bundle(),"test:faster","already committed domain source retained");
            t.runtime.close();near(h,attribute.getValue(),base,"close still recognizes last written projection");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:native_attrs_eligibility")
    public void missingUnsupportedAndIneligibleRecipientsAreReportedAndRegistryErrorsFailInstallation(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            var target=t.cow(3,43,3);bind(t,"reach","reach",target);
            h.assertTrue(t.runtime.nativeAttributeReport().stream().anyMatch(r->r.holder().equals(id(target))&&r.calculation().binding().id().equals("test:reach")&&r.outcome()==MinecraftAttributeProjection.Outcome.UNSUPPORTED_ATTRIBUTE&&r.nativeValue().isEmpty()),"missing native attribute fabricated a value");
            bind(t,"fast","fast",t.owner);var speed=t.owner.getAttribute(Attributes.MOVEMENT_SPEED);double base=speed.getBaseValue();t.owner.setGameMode(GameType.SPECTATOR);t.runtime.prepare();near(h,speed.getValue(),base,"spectator projection removed");
            h.assertTrue(t.runtime.nativeAttributeReport().stream().anyMatch(r->r.holder().equals(id(t.owner))&&r.outcome()==MinecraftAttributeProjection.Outcome.INELIGIBLE),"ineligible status missing");
            t.owner.setGameMode(GameType.SURVIVAL);t.runtime.prepare();near(h,speed.getValue(),base*1.5,"eligible owner reconciled again");
            bind(t,"mob-fast","fast",target);var mobSpeed=target.getAttribute(Attributes.MOVEMENT_SPEED);double mobBase=mobSpeed.getBaseValue();target.discard();t.runtime.prepare();near(h,mobSpeed.getValue(),mobBase,"removed entity cleaned up");
            h.assertTrue(t.runtime.nativeAttributeReport().stream().anyMatch(r->r.holder().equals(id(target))&&r.outcome()==MinecraftAttributeProjection.Outcome.MISSING_TARGET),"removed target not reported");settled(h,t);
            t.runtime.close();var data=ThreadedSpikeGameTest.json("native_attributes");data.getAsJsonArray("native_attributes").get(0).getAsJsonObject().addProperty("attribute","test:absent");var p=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,data).getOrThrow();
            boolean rejected=false;try{MinecraftEffectRuntime.install(h.getLevel(),p,EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),t.world,MinecraftEffectRuntime::nativeSource);}catch(RuntimeException expected){rejected=true;}
            h.assertTrue(rejected&&MinecraftEffectRuntime.installed(h.getLevel()).isEmpty(),"bad registry binding left an installed runtime");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:native_attrs_conflict")
    public void anExternallyOverwrittenProjectionIsNeitherSilentlyReplacedNorDeleted(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            bind(t,"fast","fast",t.owner);var attribute=t.owner.getAttribute(Attributes.MOVEMENT_SPEED);var original=projections(attribute).getFirst();
            var external=new AttributeModifier(original.id(),.75,original.operation());attribute.removeModifier(original.id());attribute.addTransientModifier(external);
            t.runtime.prepare();h.assertTrue(t.runtime.failure().isPresent(),"external overwrite did not report conflict");h.assertValueEqual(attribute.getModifier(external.id()),external,"conflict was silently overwritten");
            t.runtime.close();h.assertValueEqual(attribute.getModifier(external.id()),external,"close deleted external modification");h.succeed();
        }
    }
}
