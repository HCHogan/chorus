package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

/** Physical field creation, actual server cadence, fractional status, independent magma and Heat Rises. */
public class SolarGrenadeGameTest {
    static final String D="chorus_d2:", ABILITY=D+"solar_grenade", FIELD=D+"solar_grenade_field";
    static void prepare(JsonObject data){
        FireboltGameTest.prepare(data);
        for(String name:List.of("solar_grenade","solar_grenade_energy")){var part=ThreadedSpikeGameTest.json(name);if(name.equals("solar_grenade"))AscensionGameTest.calibrate(name,part);GamblersDodgeGameTest.merge(data,part);}
        GamblersDodgeGameTest.merge(data,ThreadedSpikeGameTest.json("solar_grenade_test_calibration").getAsJsonObject("program"));AscensionGameTest.version(data);
    }
    static class Scenario extends FireboltGameTest.Scenario {
        Scenario(GameTestHelper h)throws Exception {this(h,SolarGrenadeGameTest::prepare);}
        Scenario(GameTestHelper h,java.util.function.Consumer<JsonObject> prepare)throws Exception {super(h,prepare,ABILITY);}
        EffectState state(){return t.runtime.state().engine().domain();}
        List<BuffInstance> fields(){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(FIELD)).toList();}
        WorldPosition center(){return fields().getFirst().components().positions().get("anchor").orElseThrow();}
        @Override void impact(){t.owner.setYRot(0);t.owner.setXRot(90);cast();var shell=t.projectiles.getLast();for(int i=0;i<12&&!shell.isRemoved();i++)shell.tick();h.assertTrue(shell.isRemoved()&&!fields().isEmpty(),"real floor contact must create a field");}
        LivingEntity at(double dx){var target=enemy(1);var center=center();target.setPos(center.x()+dx,center.y()-target.getBbHeight()*.5,center.z());return target;}
        List<DamageCommand> hits(String phase){return t.hits.stream().filter(c->c.tags().contains(D+"solar_grenade_"+phase+"_damage")).toList();}
        double scorch(LivingEntity e){return buff("scorch",e).map(BuffInstance::quantity).orElse(0.);}
    }
    static void scenario(GameTestHelper h,java.util.function.Consumer<Scenario> run){ready(h,()->{var s=new Scenario(h);try{run.accept(s);}catch(RuntimeException|Error e){s.close();throw e;}});}
    static float repeated(float health,float amount,int times){for(int i=0;i<times;i++)health-=amount;return health;}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_field",maxTicks=120)
    public void paidPhysicalImpactCreatesFixedFieldWithThirteenRealPulsesAndExpiryScorch(GameTestHelper h){scenario(h,s->{
        s.impact();var target=s.at(1);var center=s.center();s.t.owner.setPos(s.t.owner.position().add(20,0,0));
        s.later(19,()->{h.assertTrue(s.hits("field").isEmpty(),"field tick before startup");near(h,target.getHealth(),1000,"no pre-start HP loss");});
        s.later(83,()->{h.assertValueEqual(s.hits("field").size(),12,"twelve pulses before last logical deadline");near(h,s.scorch(target),0,"PvE field granted periodic Scorch");h.assertValueEqual(s.center(),center,"field followed caster");});
        s.finish(86,()->{h.assertValueEqual(s.hits("field").size(),13,"thirteen calibrated pulses");near(h,target.getHealth(),repeated(1000,7.8f,13),"native field HP");h.assertTrue(s.fields().isEmpty(),"field expiry");near(h,s.scorch(target),17,"expiry Scorch");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_impact",maxTicks=60)
    public void physicalImpactUsesFractionalAshesAndSeparateImpactFalloff(GameTestHelper h){scenario(h,s->{
        var near=s.enemy(2);var edge=s.enemy(4.5);s.bind(D+"ember_of_ashes");s.impact();h.assertValueEqual(s.hits("impact").size(),2,"two actual blast recipients");near(h,s.scorch(near),21.5,"fractional initial Ashes");near(h,s.scorch(edge),21.5,"independent outer Ashes");h.assertTrue(near.getHealth()<edge.getHealth()&&edge.getHealth()<1000,"initial distance falloff");s.finish(2,()->h.assertTrue(s.hits("field").isEmpty(),"field cannot inherit initial blast"));
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_guardian",maxTicks=65)
    public void realGuardianReceivesOneThenSevenAndAHalfScorchFromTheField(GameTestHelper h){scenario(h,s->{
        s.impact();var player=NativeMeleeGameTest.player(h);s.t.entities.add(player);player.setNoGravity(true);var center=s.center();player.setPos(center.x()+1,center.y()-player.getBbHeight()*.5,center.z());player.getAbilities().invulnerable=false;player.setInvulnerableTime(0);
        s.later(16,()->near(h,s.scorch(player),1,"guardian first Scorch"));s.later(27,()->near(h,s.scorch(player),8.5,"guardian fractional second application"));s.finish(38,()->{near(h,s.scorch(player),16,"guardian third application");h.assertTrue(s.hits("field").stream().anyMatch(c->c.target().equals(player.getUUID().toString())),"actual guardian field damage");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_members",maxTicks=65)
    public void fieldReselectsMovingEnemiesAndRejectsAllies(GameTestHelper h){scenario(h,s->{
        s.impact();var gone=s.at(1);var ally=s.at(2);h.getLevel().getScoreboard().addPlayerToTeam(ally.getScoreboardName(),s.team);var late=s.at(5);
        s.later(21,()->{h.assertValueEqual(s.hits("field").size(),1,"initial eligible member");gone.setPos(gone.position().add(10,0,0));var center=s.center();late.setPos(center.x()+3,center.y()-late.getBbHeight()*.5,center.z());});
        s.finish(27,()->{h.assertValueEqual(s.hits("field").stream().map(DamageCommand::target).toList(),List.of(gone.getUUID().toString(),late.getUUID().toString()),"current field membership");near(h,ally.getHealth(),1000,"ally excluded");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_overlap",maxTicks=65)
    public void twoPaidPhysicalFieldsKeepSeparateLivesButShareTheTargetDotGate(GameTestHelper h){scenario(h,s->{
        s.impact();s.full();s.impact();h.assertValueEqual(s.fields().size(),2,"independent cast fields");var target=s.at(1);
        s.finish(22,()->{h.assertValueEqual(s.hits("field").size(),1,"overlapping fields double dealt a tick");near(h,target.getHealth(),992.2,"one native pulse");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_magma",maxTicks=100)
    public void touchEmitsPhysicalMagmaThatBurstsWithoutFalloffAndKeepsGrenadeCredit(GameTestHelper h){scenario(h,s->{
        s.touch();s.impact();s.t.runtime.unbind("touch");AscensionGameTest.choose(s.t,D+"grenade",null);
        s.later(21,()->{h.assertValueEqual(s.t.projectiles.size(),2,"one emitted magma projectile");var orb=s.t.projectiles.getLast();h.assertTrue(orb.getDeltaMovement().y>0,"magma never launched upwards");var origin=orb.position();
            // Intercept the real flight with an entity; the neighboring recipient has a different blast distance.
            var direction=orb.getDeltaMovement().normalize();var next=origin.add(direction.scale(1.2));var side=new net.minecraft.world.phys.Vec3(-direction.z,0,direction.x).normalize().scale(.9);
            var first=s.enemy(1);var second=s.enemy(2);first.setPos(next.x,next.y-first.getBbHeight()*.5,next.z);second.setPos(next.x+side.x,next.y-second.getBbHeight()*.5,next.z+side.z);
            for(int i=0;i<40&&!orb.isRemoved();i++)orb.tick();h.assertTrue(orb.isRemoved(),"magma failed physical collision");h.assertValueEqual(s.hits("magma").size(),2,"physical magma must hit both nearby recipients");near(h,first.getHealth(),987.5,"contacted magma recipient");near(h,second.getHealth(),987.5,"magma must not have radial falloff");h.assertTrue(s.hits("magma").stream().allMatch(c->c.source().ability().equals(ABILITY)&&c.killTags().contains("chorus:grenade_kill")),"magma lost source credit");
        });s.finish(23,()->h.assertTrue(!s.fields().isEmpty(),"unequipping ended the Touch field"));
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_touch",maxTicks=155)
    public void touchExtendsThePhysicalFieldAndKeepsEmittingAfterAspectRemoval(GameTestHelper h){scenario(h,s->{
        s.touch();s.impact();s.t.runtime.unbind("touch");AscensionGameTest.choose(s.t,D+"grenade",null);s.later(90,()->{h.assertValueEqual(s.fields().size(),1,"Touch lifetime lost after unbind");h.assertTrue(s.t.projectiles.size()>4,"later magma emissions missing");});s.finish(127,()->{h.assertTrue(s.fields().isEmpty(),"Touch field did not expire");h.assertTrue(s.t.projectiles.size()>=9,"Touch emissions ended early");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_heat",maxTicks=70)
    public void heatRisesConsumesThisRealGrenadeWithoutLaunchingOrCreatingAField(GameTestHelper h){scenario(h,s->{
        s.t.runtime.bind(new EffectSource("heat",D+"heat_rises_aspect",s.owner(),new BuffInstance.Origin(s.owner(),"heat","",""),Set.of(),Map.of("hold_time",new com.imdomestic.chorus.stat.Measure(.3,com.imdomestic.chorus.stat.Unit.SECOND),"receipt_seconds",new com.imdomestic.chorus.stat.Measure(60,com.imdomestic.chorus.stat.Unit.SECOND))));
        h.assertValueEqual(s.t.runtime.abilityInput(s.t.owner,D+"grenade",AbilityInput.Edge.PRESS,1).outcome(),AbilityInput.Outcome.PRESSED,"start hold");
        s.later(7,()->{var result=s.t.runtime.abilityInput(s.t.owner,D+"grenade",AbilityInput.Edge.RELEASE,1);h.assertValueEqual(result.cast().orElseThrow().resolved(),D+"heat_rises_consume","consume grenade");s.finish(3,()->{near(h,s.energy(),.15/199.5,"real grenade cost and recharge");h.assertTrue(s.t.projectiles.isEmpty()&&s.fields().isEmpty(),"consumption still threw grenade");h.assertTrue(s.buff("heat_rises",s.t.owner).isPresent(),"Heat Rises not active");});});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_unknown",maxTicks=65)
    public void unknownNativeFieldHitPreservesCommittedHpAndCannotReplay(GameTestHelper h){scenario(h,s->{
        s.impact();var target=s.at(1);s.t.failAfterDamage=true;
        s.later(22,()->{try(s){h.assertTrue(s.t.runtime.failure().isPresent(),"unknown result did not fail runtime");near(h,target.getHealth(),992.2,"committed field HP loss");int count=s.hits("field").size();MinecraftEffectRuntime.tick(h.getLevel());h.assertValueEqual(s.hits("field").size(),count,"unknown hit replayed");h.succeed();}});
    });}
}
