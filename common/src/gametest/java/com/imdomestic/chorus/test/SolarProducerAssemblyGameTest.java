package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

/** Real Scorch deaths with the selecting ability removed before the status is captured. */
public class SolarProducerAssemblyGameTest {
    static final String D="chorus_d2:";
    static void systemSource(PhoenixDiveGameTest.Scenario s) {
        long count=s.t.runtime.state().engine().domain().sources().values().stream().filter(x->x.holder().equals(s.owner())&&x.bundle().equals(D+"solar_scaling")).count();
        s.h.assertValueEqual(count,1L,"one owner Solar source, independent of all ability selections");
    }
    static void expectScorchDeath(PhoenixDiveGameTest.Scenario s,LivingEntity target,String ability,String kind) {
        systemSource(s);s.h.assertTrue(target.isAlive()&&s.buff("scorch",target).isPresent(),"confirmed nonlethal producer must create Scorch");
        // Isolate the already-created DoT from direct damage and later field / second-explosion damage.
        target.setHealth(.01f);int start=s.t.hits.size();String id=target.getUUID().toString();
        s.finish(12,()->{
            s.h.assertTrue(target.isDeadOrDying(),"Scorch command existed but caused no actual lethal loss");
            boolean confirmed=false;
            for(int i=start;i<s.t.hits.size();i++) {
                var hit=s.t.hits.get(i);if(!hit.target().equals(id)||!hit.tags().contains(D+"scorch_damage"))continue;
                var receipt=s.t.receipts.get(i);near(s.h,receipt.healthLoss(),.01,"actual native DoT loss");
                s.h.assertTrue(receipt.deathId().isPresent(),"native death was not confirmed");
                s.h.assertValueEqual(hit.source().ability(),D+ability,"original ability credit");
                s.h.assertTrue(hit.killTags().contains("chorus:"+kind+"_kill")&&hit.killTags().contains("chorus:ability_kill"),"derived kill category");confirmed=true;
            }
            s.h.assertTrue(confirmed,"death was caused by a different producer");systemSource(s);
        });
    }
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_assembly_firebolt",maxTicks=100)
    public void fireboltWithoutAClassSelectionKillsThroughScorchAfterItsGrenadeIsDeselected(GameTestHelper h){FireboltGameTest.scenario(h,s->{
        AscensionGameTest.choose(s.t,D+"class",null);var target=s.enemy(6);s.impact();AscensionGameTest.choose(s.t,D+"grenade",null);
        s.later(22,()->{s.finishFlights();near(h,target.getHealth(),966.3,"actual secondary bolt before DoT");expectScorchDeath(s,target,"firebolt","grenade");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_assembly_fusion",maxTicks=90)
    public void fusionWithoutAClassSelectionKeepsScorchDamageAfterDeselectionBeforeItsFuse(GameTestHelper h){FusionGrenadeGameTest.scenario(h,s->{
        AscensionGameTest.choose(s.t,D+"class",null);var target=s.attach();AscensionGameTest.choose(s.t,D+"grenade",null);
        s.later(13,()->{h.assertTrue(target.getHealth()<1000,"actual attached blast");expectScorchDeath(s,target,"fusion_grenade","grenade");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_assembly_grenade",maxTicks=70)
    public void solarGrenadeScorchKillsBeforeFieldStartupEvenWhenBothSlotsAreEmpty(GameTestHelper h){SolarGrenadeGameTest.scenario(h,s->{
        AscensionGameTest.choose(s.t,D+"class",null);var target=s.enemy(2);s.t.owner.setYRot(0);s.t.owner.setXRot(90);s.cast();AscensionGameTest.choose(s.t,D+"grenade",null);
        var shell=s.t.projectiles.getLast();for(int i=0;i<12&&!shell.isRemoved();i++)shell.tick();h.assertTrue(shell.isRemoved()&&target.getHealth()<1000,"actual Solar impact");expectScorchDeath(s,target,"solar_grenade","grenade");
    });}
    @GameCase(environment="chorus_gametest:solar_assembly_phoenix",maxTicks=70)
    public void phoenixDeselectedBeforeLandingStillProducesAConfirmedClassScorchKill(GameTestHelper h){ready(h,()->{
        var s=new PhoenixDiveGameTest.Scenario(h);try {var target=s.target(2,false);s.input("heat");s.cast();AscensionGameTest.choose(s.t,D+"class",null);s.land();near(h,target.getHealth(),90,"actual heated landing");expectScorchDeath(s,target,"phoenix_dive","class");}catch(RuntimeException|Error e){s.close();throw e;}
    });}
}
