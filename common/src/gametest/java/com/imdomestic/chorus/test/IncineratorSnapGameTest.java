package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public class IncineratorSnapGameTest {
    static final String D="chorus_d2:",ABILITY=D+"incinerator_snap";
    static void prepare(JsonObject data) {
        SunbracersGameTest.prepare(data);var part=ThreadedSpikeGameTest.json("incinerator_snap");AscensionGameTest.calibrate("incinerator_snap",part);GamblersDodgeGameTest.merge(data,part);
        GamblersDodgeGameTest.merge(data,ThreadedSpikeGameTest.json("incinerator_snap_test_calibration").getAsJsonObject("program"));AscensionGameTest.version(data);
    }
    static class Scenario extends SolarGrenadeGameTest.Scenario {
        Scenario(GameTestHelper h)throws Exception{super(h,IncineratorSnapGameTest::prepare);AscensionGameTest.choose(t,D+"melee",ABILITY);bind(D+"solar_scaling");}
        List<EffectProjectile> snap(){int before=t.projectiles.size();h.assertValueEqual(t.runtime.useAbility(t.owner,D+"melee").outcome(),AbilityUse.Outcome.ACCEPTED,"one paid Snap");near(h,AscensionGameTest.energy(t,D+"solar_melee_uses"),0,"one melee charge");var sparks=List.copyOf(t.projectiles.subList(before,t.projectiles.size()));h.assertValueEqual(sparks.size(),5,"five physical sparks");return sparks;}
        LivingEntity target(double distance){var e=enemy(distance);e.setPos(e.getX(),t.owner.getEyeY()-e.getBbHeight()*.5,e.getZ());return e;}
        void aim(){t.owner.setYRot(-90);t.owner.setXRot(0);}
        void exotic(){t.runtime.bind(new EffectSource("sun",D+"sunbracers",owner(),new BuffInstance.Origin(owner(),"sun","",""),Set.of(D+"sunbracers"),Map.of("fastball_speed_delta",new Measure(.25,Unit.DELTA))));}
        Optional<BuffInstance> readyBuff(){return buff("sunbracers_ready",t.owner);} Optional<BuffInstance> active(){return buff("sunbracers_active",t.owner);}
        List<DamageCommand> sparks(){return t.hits.stream().filter(c->c.tags().contains(D+"incinerator_snap_damage")).toList();}
        void finish(List<EffectProjectile> sparks){for(var p:sparks)for(int i=0;i<24&&!p.isRemoved();i++)p.tick();h.assertTrue(sparks.stream().allMatch(EffectProjectile::isRemoved),"all physical sparks ended");}
        EffectProjectile centerOnly(){var sparks=snap();for(int i=0;i<sparks.size();i++)if(i!=2)sparks.get(i).discard();return sparks.get(2);}
    }
    static void scenario(GameTestHelper h,java.util.function.Consumer<Scenario> body){ready(h,()->{var s=new Scenario(h);try{body.accept(s);}catch(RuntimeException|Error e){s.close();throw e;}});}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_fan",maxTicks=60)
    public void physicalFanKeepsFiveDirectionsWhileLookingUpDownOrHorizontally(GameTestHelper h){ready(h,()->{
        for(float pitch:new float[]{-90,0,90})try(var s=new Scenario(h)) {
            s.t.owner.setYRot(-37);s.t.owner.setXRot(pitch);var aim=s.t.owner.getLookAngle().normalize();var sparks=s.snap();
            h.assertValueEqual(new HashSet<>(sparks.stream().map(p->p.launch().orElseThrow().direction().orElseThrow()).toList()).size(),5,"vertical aim collapsed fan");
            for(int i=0;i<5;i++){var direction=sparks.get(i).getDeltaMovement().normalize();near(h,direction.dot(aim),Math.cos(Math.toRadians((i-2)*5)),"angle relative to original look");}
            s.t.owner.setYRot(143);s.t.owner.setXRot(25);for(var p:sparks){var before=p.getDeltaMovement();p.tick();near(h,p.getDeltaMovement().distanceTo(before),0,"accepted direction followed later camera");}
        } h.succeed();
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_ignite",maxTicks=80)
    public void fiveActualProximityExplosionsAccumulateScorchAndProduceSharedIgnition(GameTestHelper h){scenario(h,s->{
        var target=s.target(4);s.aim();var sparks=s.snap();s.finish(sparks);near(h,target.getHealth(),887.4,"five nine-HP blasts plus shared 67.6-HP ignition");near(h,s.scorch(target),0,"shared threshold consumed Scorch");
        h.assertValueEqual(s.sparks().size(),5,"one explosion from each spark");h.assertTrue(sparks.stream().allMatch(p->p.progress().entityContacts()==0),"near trigger became direct contact");
        s.finish(12,()->{var ignition=s.t.hits.stream().filter(c->c.tags().contains(D+"ignition_damage")).findFirst().orElseThrow();h.assertValueEqual(ignition.source().ability(),ABILITY,"shared ignition lost original ability");h.assertTrue(ignition.killTags().contains("chorus:melee_kill"),"derived ignition lost melee credit");h.assertTrue(target.getHealth()<955,"ignition did not affect native health");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_scorch_kill",maxTicks=80)
    public void actualScorchKillArmsSunbracersAndPaidSolarGrenadeConsumesReady(GameTestHelper h){scenario(h,s->{
        s.exotic();var target=s.target(4);target.setHealth(9.01f);s.aim();var p=s.centerOnly();s.finish(List.of(p));h.assertTrue(target.isAlive()&&s.readyBuff().isEmpty(),"direct blast killed or invented Ready");near(h,s.scorch(target),20,"one spark applied shared Scorch");
        s.finish(12,()->{h.assertTrue(target.isDeadOrDying()&&s.readyBuff().isPresent(),"real Scorch kill did not arm");s.impact();h.assertTrue(s.readyBuff().isEmpty()&&s.active().isPresent(),"paid Solar cast did not consume Ready");near(h,s.energy(),0,"Solar charge paid");h.assertValueEqual(s.fields().size(),1,"physical Solar field");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_sunbracers",maxTicks=120)
    public void actualWarlockMeleeKillFundsRepeatedPaidSolarGrenadesThroughSunbracers(GameTestHelper h){scenario(h,s->{
        s.exotic();var target=s.target(4);target.setHealth(1);s.aim();s.finish(s.snap());h.assertTrue(target.isDeadOrDying()&&s.readyBuff().isPresent(),"Snap kill did not arm");s.impact();h.assertTrue(s.active().isPresent(),"first paid grenade did not activate");
        for(int delay:List.of(20,40,60,80))s.later(delay,()->{near(h,s.energy(),1,"burst regeneration funds next cast");s.impact();});
        s.finish(85,()->{h.assertValueEqual(s.fields().size(),5,"five paid Solar fields under explicit cadence");h.assertValueEqual(s.t.projectiles.size(),10,"five Snap sparks and five Solar throws");h.assertTrue(s.active().isEmpty(),"timed exotic did not expire");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_snapshot",maxTicks=60)
    public void acceptedSparkRetainsAshesAndSourceAfterRemovingTheMeleeSelection(GameTestHelper h){scenario(h,s->{try(s){
        s.bind(D+"ember_of_ashes");var target=s.target(4);s.aim();var p=s.centerOnly();s.t.runtime.unbind(D+"ember_of_ashes");AscensionGameTest.choose(s.t,D+"melee",null);s.finish(List.of(p));
        near(h,s.scorch(target),30,"captured source-specific Ashes");near(h,target.getHealth(),991,"frozen base blast");h.assertValueEqual(s.sparks().getFirst().source().ability(),ABILITY,"detached source credit");h.succeed();
    }});}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_geometry",maxTicks=80)
    public void rangeAndPhysicalWallBothTriggerExplosionAtTheirObservedPoint(GameTestHelper h){ready(h,()->{
        for(boolean wall:List.of(false,true))try(var s=new Scenario(h)) {
            s.aim();if(wall)for(int y=40;y<=43;y++)s.block(9,y,3);var p=s.centerOnly();var start=p.position();s.finish(List.of(p));
            near(h,p.traveled(),wall?3.5:8,"actual clipped path");h.assertTrue(!s.t.queries.isEmpty(),"terminal contact did not query explosion area");
            near(h,p.getX()-start.x,wall?3.5:8,"physical detonation point");
        }h.succeed();
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_immune",maxTicks=80)
    public void nativeImmunityAndStatusDenialDoNotCreateScorchOrSunbracers(GameTestHelper h){ready(h,()->{
        for(boolean immune:List.of(false,true))try(var s=new Scenario(h)) {
            s.exotic();var target=s.target(4);if(immune)target.setPermanentlyInvulnerable(true);else s.t.deniedStatusTargets.add(target.getUUID().toString());s.aim();s.finish(List.of(s.centerOnly()));
            near(h,target.getHealth(),immune?1000:991,"native receipt outcome");near(h,s.scorch(target),0,"status must be confirmed");h.assertTrue(s.readyBuff().isEmpty(),"nonkill armed exotic");
        }h.succeed();
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_unknown",maxTicks=60)
    public void unknownNativeKillConsumesSparkWithoutInventingConfirmedKillRewards(GameTestHelper h){scenario(h,s->{try(s){
        s.exotic();var target=s.target(4);target.setHealth(1);s.aim();var p=s.centerOnly();s.t.failAfterDamage=true;s.finish(List.of(p));
        h.assertTrue(target.isDeadOrDying()&&s.t.runtime.failure().isPresent()&&p.isRemoved(),"unknown committed native death");h.assertTrue(s.readyBuff().isEmpty(),"unconfirmed kill granted Ready");int hits=s.t.hits.size();MinecraftEffectRuntime.tick(h.getLevel());p.tick();h.assertValueEqual(s.t.hits.size(),hits,"unknown effect replayed");h.succeed();
    }});}
}
