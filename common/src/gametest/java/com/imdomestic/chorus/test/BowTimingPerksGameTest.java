package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.ExplosivePayloadGameTest.*;
import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.WeaponChargeGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.weapon.WeaponCharge;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;

/** Native charged arrows and source-scoped perk timers. Full-draw reference is Foundry; flight/damage remain synthetic. */
public class BowTimingPerksGameTest {
    static final String D="chorus_d2:", TEMPO="archers_tempo", WARM="successful_warm_up";
    static ProjectileGameTest.Harness open(GameTestHelper h,boolean enhanced)throws Exception{
        var t=ExplosiveHeadGameTest.openHead(h,data->{
            var consumer=ThreadedSpikeGameTest.json("bow_charge_weapon");data.add("equipment",consumer.get("equipment"));data.add("weapons",consumer.get("weapons"));
            RadiantGameTest.merge(data,"weapon_charge_time","bow_draw_curves","archers_tempo","successful_warm_up");
        });
        try{
            String variant=enhanced?"enhanced":"normal";var gear=new Loadout.Gear("a","test:bow",Map.of("perk","normal",TEMPO,variant,WARM,variant));
            var e=PlayerEquipment.get(t.owner);t.owner.getInventory().setItem(0,ItemStack.EMPTY);e.swap(t.owner,"test:primary",0,e.revision());
            var stack=t.owner.getInventory().getItem(0);stack.set(ChorusComponents.EQUIPMENT.get(),gear);e.swap(t.owner,"test:primary",0,e.revision());draw(t,"primary");return t;
        }catch(Exception|Error error){t.close();throw error;}
    }
    static Optional<BuffInstance> buff(ProjectileGameTest.Harness t,String name){return state(t).buffs().instances().values().stream().filter(b->b.definition().id().equals(D+name)&&b.origin().weapon().equals("a")).findFirst();}
    static void precisionKill(ProjectileGameTest.Harness t)throws Exception{
        input(t,"release");var shot=t.projectiles.getLast();var target=primary(t,shot,5);target.setHealth(1);region(t,target);contact(t,shot);t.h.assertTrue(!target.isAlive(),"physical arrow did not kill");
    }
    static void combined(GameTestHelper h,boolean enhanced)throws Exception{
        var t=open(h,enhanced);try{
            input(t,"press");h.assertValueEqual(charge(t).full().micros(),636_364L,"reference draw stat 50");h.assertValueEqual(charge(t).minimum().micros(),100_000L,"synthetic independent minimum");
            at(t,12,()->{settled(t);h.assertValueEqual(charge(t).phase(),WeaponCharge.Phase.CHARGING,"microseconds were rounded down to a tick");});
            at(t,13,()->{
                precisionKill(t);long now=state(t).buffs().timeMicros();h.assertValueEqual(buff(t,TEMPO).orElseThrow().deadline()-now,enhanced?4_000_000L:3_000_000L,"precision hit duration");
                long original=buff(t,WARM).orElseThrow().deadline();h.assertValueEqual(original-now,6_000_000L,"first kill always six seconds");
                at(t,3,()->{input(t,"press");h.assertValueEqual(charge(t).full().micros(),298_296L,"reference time times .75 times .625");h.assertValueEqual(charge(t).minimum().micros(),100_000L,"perks must not shorten independent minimum");
                    at(t,5,()->{settled(t);h.assertValueEqual(charge(t).phase(),WeaponCharge.Phase.CHARGING,"product duration became ready early");});
                    at(t,6,()->{try(t){precisionKill(t);h.assertValueEqual(buff(t,WARM).orElseThrow().deadline(),original+(enhanced?5_000_000L:4_000_000L),"second kill extends old deadline, not reset from now");h.assertValueEqual(ammo(t),8,"two paired releases pay twice");h.assertValueEqual(t.projectiles.size(),2,"two real arrows");h.succeed();}});
                });
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:bow_perks_normal",maxTicks=40)
    public void normalPrecisionKillsShortenTheNextPhysicalDrawAndExtendWarmup(GameTestHelper h)throws Exception{combined(h,false);}
    @GameCase(environment="chorus_gametest:bow_perks_enhanced",maxTicks=40)
    public void enhancedPrecisionAndKillTimersUseFourAndFiveSeconds(GameTestHelper h)throws Exception{combined(h,true);}
    @GameCase(environment="chorus_gametest:bow_perks_expiry",maxTicks=110)
    public void nonlethalPrecisionStartsTempoAndItsExpiryDoesNotChangeAnAcceptedDraw(GameTestHelper h)throws Exception{
        var t=open(h,false);try{
            input(t,"press");at(t,13,()->{input(t,"release");var shot=t.projectiles.getLast();var target=primary(t,shot,5);region(t,target);contact(t,shot);near(h,target.getHealth(),85,"nonlethal direct precision");h.assertTrue(buff(t,WARM).isEmpty(),"nonlethal hit started warmup");
                at(t,59,()->{input(t,"press");long accepted=charge(t).fullAt();h.assertValueEqual(charge(t).full().micros(),477_273L,"tempo only scalar");
                    at(t,1,()->{settled(t);h.assertTrue(buff(t,TEMPO).isEmpty(),"tempo did not expire at three seconds");h.assertValueEqual(charge(t).fullAt(),accepted,"expiry resampled accepted timing");});
                    at(t,10,()->{try(t){settled(t);h.assertValueEqual(charge(t).phase(),WeaponCharge.Phase.READY,"accepted draw missed its deadline");input(t,"cancel");input(t,"press");h.assertValueEqual(charge(t).full().micros(),636_364L,"new draw sees expired buff");h.succeed();}});
                });
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:bow_perks_stow",maxTicks=40)
    public void actualStowRemovesBothAndTheAlreadyFlyingPrecisionKillCannotRegrant(GameTestHelper h)throws Exception{
        var t=open(h,false);try{
            input(t,"press");at(t,13,()->{precisionKill(t);at(t,3,()->{input(t,"press");at(t,6,()->{try(t){
                input(t,"release");draw(t,null);h.assertTrue(buff(t,TEMPO).isEmpty()&&buff(t,WARM).isEmpty(),"stow kept active perks");
                var shot=t.projectiles.getLast();var target=primary(t,shot,5);target.setHealth(1);region(t,target);contact(t,shot);h.assertTrue(!target.isAlive(),"late arrow missed");h.assertTrue(buff(t,TEMPO).isEmpty()&&buff(t,WARM).isEmpty(),"late hit regranted stowed perks");
                draw(t,"primary");h.assertValueEqual(ammo(t),8,"stow changed committed payment");h.succeed();
            }});});});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:bow_perks_delayed",maxTicks=35)
    public void delayedBodyExplosionKillStartsWarmupWithoutPrecisionTempo(GameTestHelper h)throws Exception{
        var t=open(h,false);try{
            input(t,"press");at(t,13,()->{input(t,"release");var shot=t.projectiles.getLast();var target=primary(t,shot,5);target.setHealth(10);contact(t,shot);near(h,target.getHealth(),5,"direct body remains alive");h.assertTrue(buff(t,WARM).isEmpty(),"warmup started before kill");
                at(t,4,()->{try(t){settled(t);h.assertTrue(!target.isAlive(),"delayed physical explosion failed to kill");h.assertValueEqual(buff(t,WARM).orElseThrow().deadline()-state(t).buffs().timeMicros(),6_000_000L,"delayed weapon credit");h.assertTrue(buff(t,TEMPO).isEmpty(),"explosion invented precision");input(t,"press");h.assertValueEqual(charge(t).full().micros(),397_728L,"warmup alone affects next draw");h.succeed();}});
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
}
