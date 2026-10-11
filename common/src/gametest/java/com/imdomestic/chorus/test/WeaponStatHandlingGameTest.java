package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.SurplusGameTest.*;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

/** Reference D2 handling curves are consumed by actual physical equipment changes. */
public class WeaponStatHandlingGameTest {
    static final String D="chorus_d2:";
    static ProjectileGameTest.Harness open(GameTestHelper h)throws Exception {
        var t=new ProjectileGameTest.Harness(h,"surplus",data->{
            prepare(data);RadiantGameTest.merge(data,"character_stats","amplified","weapon_stat_handling","weapon_handling_curves","weapon_stat_handling_inputs","armor_charge","armor_charge_mods","armor_charge_inputs","orb_of_power");
            SolarFragmentStatsGameTest.armor(data);
            for(var profile:ThreadedSpikeGameTest.json("starvation_inputs").getAsJsonArray("profiles"))
                if(profile.getAsJsonObject().get("id").getAsString().equals(D+"orb_of_power_base_energy"))data.getAsJsonArray("profiles").add(profile);
            var bindings=ThreadedSpikeGameTest.json("weapon_handling_test_calibration");
            for(var w:data.getAsJsonArray("weapons")){var weapon=w.getAsJsonObject();weapon.add("handling",bindings.get(weapon.get("item").getAsString()).deepCopy());}
        },true);
        try {start(t,false,1,1,1);draw(t,null);IncandescentGameTest.bind(t,"handling_stat",D+"weapon_stat_handling","");
            IncandescentGameTest.bind(t,"amplified_inputs","test:amplified_inputs","");t.runtime.bind(ArmorChargeGameTest.system(owner(t)));IncandescentGameTest.bind(t,"armor_inputs","test:armor_charge_inputs","");return t;
        }catch(Exception|Error error){t.close();throw error;}
    }
    static void draw(ProjectileGameTest.Harness t,String slot){WeaponHandlingGameTest.draw(t,slot);}
    static WeaponHandling.Plan plan(ProjectileGameTest.Harness t){return state(t).handling().get(owner(t));}
    static void armor(ProjectileGameTest.Harness t,double points){WeaponStatReloadGameTest.armor(t,points);}
    static void amplify(ProjectileGameTest.Harness t,double duration){t.runtime.start(new RuleEngine.Signal("test:amplified",new EffectEvent(owner(t),owner(t),new BuffInstance.Origin(owner(t),"input","",""),Set.of(),Map.of("duration",new Measure(duration,Unit.SECOND)))));}
    static int remainingTicks(ProjectileGameTest.Harness t){return Math.toIntExact((plan(t).dueAt()-state(t).buffs().timeMicros()+49_999)/50_000);}
    static void selection(ProjectileGameTest.Harness t,String slot){WeaponHandlingGameTest.settled(t);WeaponHandlingGameTest.agrees(t);t.h.assertValueEqual(WeaponHandlingGameTest.drawn(t),Optional.ofNullable(slot).map(s->"test:"+s),"actual held equipment");}
    static double readyA(double h){return .51985381-.00279338*h;}
    static double stowA(double h){return .48414822-.00268436*h;}
    static double readyB(double h){return .5388744939-.003271255061*h;}

    @GameCase(environment="chorus_gametest:weapon_stat_handling_physical",maxTicks=65)
    public void actualSurplusSwitchKeepsStowTimeAndUsesCurrentArmorForTheOtherWeapon(GameTestHelper h)throws Exception {
        var t=open(h);try {armor(t,100);draw(t,"primary");near(h,plan(t).duration().value(),readyA(70)*.9,"Surplus and Weapons ready");
            RampageGameTest.at(t,remainingTicks(t),()->{selection(t,"primary");draw(t,"secondary");var accepted=plan(t);near(h,accepted.duration().value(),stowA(70)*.9,"own Surplus and Weapons stow");
                h.assertValueEqual(t.runtime.fire(t.owner).outcome(),WeaponFire.Outcome.RESTRICTED,"stowing blocks fire");h.assertValueEqual(t.runtime.reload(t.owner).outcome(),WeaponReload.Outcome.RESTRICTED,"stowing blocks manual reload");
                armor(t,0);use(t,"melee");h.assertValueEqual(plan(t),accepted,"equipment and energy changes cannot retime stow");
                RampageGameTest.at(t,remainingTicks(t)-1,()->selection(t,"primary"));
                RampageGameTest.at(t,remainingTicks(t),()->{selection(t,null);h.assertValueEqual(plan(t).phase(),WeaponHandling.Phase.READY,"second phase");near(h,plan(t).duration().value(),readyB(10),"shotgun has its own curve and no rifle Surplus");
                    armor(t,100);RampageGameTest.at(t,remainingTicks(t),()->{try(t){selection(t,"secondary");h.assertTrue(plan(t)==null,"switch finished");h.assertValueEqual(t.runtime.fire(t.owner).outcome(),WeaponFire.Outcome.ACCEPTED,"new gun can fire");
                        h.assertValueEqual(t.projectiles.size(),1,"actual projectile fired");h.assertValueEqual(state(t).ammunition().get("b").magazine(),4,"new weapon pays ammunition");h.assertValueEqual(state(t).ammunition().get("a").magazine(),5,"old weapon did not fire");h.succeed();}});
                });
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:weapon_stat_handling_floor",maxTicks=50)
    public void amplifiedAndSurplusReachTheFinalStowFloorWhileReadyRetainsBothScalars(GameTestHelper h)throws Exception {
        var t=open(h);try {armor(t,200);amplify(t,15);draw(t,"primary");near(h,plan(t).duration().value(),readyA(100)*.95*.9,"ready passes below the 100 Handling duration");
            RampageGameTest.at(t,remainingTicks(t),()->{selection(t,"primary");draw(t,"secondary");var accepted=plan(t);near(h,accepted.duration().value(),stowA(100),"all stow multipliers finish at floor");
                h.assertTrue(accepted.calculation().orElseThrow().steps().get(3).output().value()<accepted.duration().value(),"duration multipliers were applied before the final floor");
                RampageGameTest.at(t,remainingTicks(t),()->{selection(t,null);near(h,plan(t).duration().value(),readyB(50)*.95*.9,"target weapon gets Amplified but not the other weapon's Surplus");
                    RampageGameTest.at(t,remainingTicks(t),()->{try(t){selection(t,"secondary");h.assertTrue(plan(t)==null,"both scaled phases finished");h.succeed();}});
                });
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:weapon_stat_handling_font",maxTicks=55)
    public void realOrbFontAndChargeConsumptionChangeTheFollowingHandlingPhase(GameTestHelper h)throws Exception {
        var t=open(h);try {armor(t,50);for(int i=0;i<3;i++)ArmorChargeGameTest.mod(t,"weapons_font",i);ArmorChargeGameTest.orb(t);
            near(h,SolarFragmentStatsGameTest.points(t,"weapons"),100,"real Orb activates Font");draw(t,"primary");var accepted=plan(t);near(h,accepted.duration().value(),readyA(70)*.9,"Font contributes to accepted ready");
            ArmorChargeGameTest.spend(t,1);near(h,SolarFragmentStatsGameTest.points(t,"weapons"),50,"shared pool payment removes Font");h.assertValueEqual(plan(t),accepted,"payment leaves accepted ready intact");
            RampageGameTest.at(t,remainingTicks(t),()->{selection(t,"primary");draw(t,"secondary");near(h,plan(t).duration().value(),stowA(70)*.95,"stow reads armor without Font");
                RampageGameTest.at(t,remainingTicks(t),()->{selection(t,null);near(h,plan(t).duration().value(),readyB(10)*.95,"next weapon reads the same remaining character stat");
                    RampageGameTest.at(t,remainingTicks(t),()->{try(t){selection(t,"secondary");h.assertValueEqual(ArmorChargeGameTest.charges(t),0,"handling cannot recreate the spent charge");h.succeed();}});
                });
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:weapon_stat_handling_expiry",maxTicks=45)
    public void actualAmplifiedExpiryDoesNotRetimeReadyButTheFollowingStowLosesBothBenefits(GameTestHelper h)throws Exception {
        var t=open(h);try {armor(t,100);amplify(t,.1);draw(t,"primary");var accepted=plan(t);near(h,accepted.duration().value(),readyA(100)*.95*.9,"Amplified point and duration benefit");
            RampageGameTest.at(t,2,()->{WeaponHandlingGameTest.settled(t);h.assertValueEqual(plan(t),accepted,"ready deadline survived expiry");h.assertTrue(state(t).buffs().instances().values().stream().noneMatch(b->b.definition().id().equals(D+"amplified")),"Amplified expired on actual tick");});
            RampageGameTest.at(t,remainingTicks(t),()->{selection(t,"primary");draw(t,"secondary");near(h,plan(t).duration().value(),stowA(70)*.9,"next phase loses Amplified points and scalar");
                RampageGameTest.at(t,remainingTicks(t),()->{try(t){selection(t,null);near(h,plan(t).duration().value(),readyB(10)*.9,"target ready uses current state");h.succeed();}});
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:weapon_stat_handling_unknown",maxTicks=30)
    public void unknownReactionAfterScaledReadyKeepsCommittedPhysicalSelectionWithoutReplay(GameTestHelper h)throws Exception {
        var t=open(h);try {armor(t,100);amplify(t,15);IncandescentGameTest.bind(t,"probe","test:handling_probe","");draw(t,"primary");t.failAfterCue=true;
            h.runAfterDelay(remainingTicks(t)+1,()->{try(t){h.assertTrue(t.runtime.failure().isPresent(),"unknown drawn reaction stops runtime");WeaponHandlingGameTest.agrees(t);
                h.assertValueEqual(WeaponHandlingGameTest.drawn(t),Optional.of("test:primary"),"physical ready commit survives");h.assertTrue(plan(t)==null,"committed ready is not pending");h.assertValueEqual(t.cues.size(),1,"drawn reaction applied once");
                int operations=t.operations.size();t.runtime.prepare();t.runtime.prepare();h.assertValueEqual(t.operations.size(),operations,"unknown reaction not replayed");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
}
