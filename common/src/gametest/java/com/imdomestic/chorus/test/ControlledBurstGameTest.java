package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.ExplosivePayloadGameTest.*;
import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.WeaponChargeGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.weapon.WeaponCharge;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

/** Adaptive reference charge/bolt timing with synthetic damage and flight; not a complete D2 fusion archetype. */
public class ControlledBurstGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h,boolean enhanced,boolean warm)throws Exception{
        var t=new ProjectileGameTest.Harness(h,"fusion_burst_weapon",data->RadiantGameTest.merge(data,"controlled_burst","weapon_charge_time","successful_warm_up"),true);
        try{
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX()+3.5,t.owner.getY(),t.owner.chunkPosition().getMinBlockZ()+3.5);t.owner.setGameMode(GameType.SURVIVAL);
            var choices=new HashMap<>(Map.of("perk",enhanced?"enhanced":"normal"));if(warm)choices.put("warmup","normal");
            var stack=new ItemStack(Items.CROSSBOW);stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear("a","test:fusion",choices));t.owner.getInventory().setItem(0,stack);var e=PlayerEquipment.get(t.owner);e.swap(t.owner,"test:primary",0,e.revision());draw(t,"primary");return t;
        }catch(Exception|Error error){t.close();throw error;}
    }
    static Optional<BuffInstance> buff(ProjectileGameTest.Harness t){return state(t).buffs().instances().values().stream().filter(b->b.definition().id().equals("chorus_d2:controlled_burst")&&b.origin().weapon().equals("a")).findFirst();}
    static void press(ProjectileGameTest.Harness t){t.runtime.charge(t.owner,WeaponCharge.Edge.PRESS);}
    static void allHits(GameTestHelper h,boolean enhanced)throws Exception{
        var t=open(h,enhanced,false);try{
            press(t);at(t,13,()->{settled(t);h.assertTrue(t.projectiles.isEmpty(),"fusion fired before full charge");h.assertValueEqual(ammo(t),5,"charge acceptance paid early");});
            at(t,14,()->{settled(t);h.assertValueEqual(t.projectiles.size(),1,"first boundary did not launch exactly first bolt");h.assertValueEqual(ammo(t),4,"one round per burst");});
            at(t,18,()->{settled(t);h.assertValueEqual(t.projectiles.size(),7,"scheduled seven-member burst");var target=primary(t,t.projectiles.getFirst(),12);
                for(int i=0;i<7;i++){contact(t,t.projectiles.get(i));if(i<6)h.assertTrue(buff(t).isEmpty(),"perk activated without all bolts");}
                near(h,target.getHealth(),30,"seven real ten-damage receipts");long remaining=buff(t).orElseThrow().deadline()-state(t).buffs().timeMicros();h.assertValueEqual(remaining,enhanced?2_500_000L:2_000_000L,"normal/enhanced duration");
                h.assertTrue(state(t).shotGroups().isEmpty(),"fully resolved burst leaked its group");
                at(t,9,()->{press(t);near(h,charge(t).full().duration().value(),.6003,"Controlled Burst charge multiplier");
                    at(t,13,()->{try(t){settled(t);h.assertTrue(t.projectiles.size()>=8,"next charged bolt missing");contact(t,t.projectiles.get(7));near(h,target.getHealth(),18,"next actual bolt gains twenty percent");h.assertValueEqual(ammo(t),3,"two accepted bursts pay twice");h.succeed();}});
                });
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:controlled_burst_normal",maxTicks=60)
    public void normalFullBurstHitsGrantDamageAndChargeTimeToTheNextNativeShot(GameTestHelper h)throws Exception{allHits(h,false);}
    @GameCase(environment="chorus_gametest:controlled_burst_enhanced",maxTicks=60)
    public void enhancedFullBurstUsesItsLongerDurationWithTheSameMultipliers(GameTestHelper h)throws Exception{allHits(h,true);}
    @GameCase(environment="chorus_gametest:controlled_burst_kill",maxTicks=35)
    public void earlyNativeKillChangesLaterBoltDamageAndCombinesWithWarmup(GameTestHelper h)throws Exception{
        var t=open(h,false,true);try{press(t);
            at(t,14,()->{settled(t);var target=primary(t,t.projectiles.getFirst(),12);target.setHealth(1);contact(t,t.projectiles.getFirst());h.assertTrue(!target.isAlive()&&buff(t).isPresent(),"actual first-bolt kill did not activate");target.discard();
                at(t,1,()->{settled(t);var healthy=primary(t,t.projectiles.get(1),12);contact(t,t.projectiles.get(1));near(h,healthy.getHealth(),88,"later bolt captured current perk damage");
                    at(t,12,()->{try(t){settled(t);press(t);near(h,charge(t).full().duration().value(),.667*.9*.625,"independent charge families multiply");h.assertValueEqual(ammo(t),4,"new charge does not pay yet");h.succeed();}});
                });
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:controlled_burst_stow",maxTicks=75)
    public void stowingRetiresUnlaunchedMembersButAnAlreadyFlyingKillKeepsItsCredit(GameTestHelper h)throws Exception{
        var t=open(h,false,false);try{press(t);
            at(t,14,()->{settled(t);draw(t,null);draw(t,"primary");
                at(t,4,()->{settled(t);h.assertValueEqual(t.projectiles.size(),1,"redraw restarted the retired salvo");var target=primary(t,t.projectiles.getFirst(),12);target.setHealth(1);draw(t,null);contact(t,t.projectiles.getFirst());h.assertTrue(buff(t).isPresent(),"stowed flying weapon kill lost its source");
                    at(t,47,()->{try(t){settled(t);h.assertTrue(state(t).shotGroups().isEmpty(),"incomplete group did not expire");h.assertValueEqual(t.projectiles.size(),1,"expired burst produced late shots");h.assertValueEqual(ammo(t),4,"stow changed payment");h.succeed();}});
                });
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:controlled_burst_geometry",maxTicks=30)
    public void eachNativeBoltReadsCurrentMuzzleWhileRespawnCannotContinueTheOldLife(GameTestHelper h)throws Exception{
        var t=open(h,false,false);try{press(t);
            at(t,14,()->{settled(t);var first=t.projectiles.getFirst().launch().orElseThrow();t.owner.setPos(t.owner.getX()+3,t.owner.getY(),t.owner.getZ());
                at(t,1,()->{settled(t);var second=t.projectiles.get(1).launch().orElseThrow();near(h,second.position().orElseThrow().x()-first.position().orElseThrow().x(),3,"per-bolt current muzzle");int emitted=t.projectiles.size();
                    t.owner.setHealth(0);t.owner.connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));var replacement=t.owner.connection.player;t.entities.add(replacement);
                    h.assertTrue(replacement!=t.owner,"native respawn did not change life");
                    at(t,3,()->{try(t){settled(t);h.assertValueEqual(t.projectiles.size(),emitted,"new life inherited old pending bolts");h.assertValueEqual(ammo(t),4,"respawn replayed charge payment");h.succeed();}});
                });
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:controlled_burst_unknown",maxTicks=30)
    public void unknownNativeHitRetainsAppliedDamageAndCannotReplayTheSalvo(GameTestHelper h)throws Exception{
        var t=open(h,false,false);try{press(t);
            at(t,18,()->{try(t){settled(t);var target=primary(t,t.projectiles.getFirst(),12);t.failAfterDamage=true;var shot=t.projectiles.getFirst();for(int i=0;i<35&&!shot.isRemoved();i++)shot.tick();near(h,target.getHealth(),90,"committed native damage retained");h.assertTrue(t.runtime.failure().isPresent(),"unknown grouped hit did not stop runtime");int operations=t.operations.size();t.runtime.prepare();h.assertValueEqual(t.operations.size(),operations,"unknown salvo replayed");h.assertValueEqual(ammo(t),4,"unknown damage refunded round");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
}
