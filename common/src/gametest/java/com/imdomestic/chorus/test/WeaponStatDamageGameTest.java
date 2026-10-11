package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.ArmorChargeGameTest.*;
import static com.imdomestic.chorus.test.IncandescentGameTest.*;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;

/** Physical armor, ammunition origins and current recipients feed the shared damage stages. */
public class WeaponStatDamageGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h)throws Exception {
        var t=SolarDamageScalingGameTest.openSolar(h,data->{
            RadiantGameTest.merge(data,"weapon_stat_damage");SolarFragmentStatsGameTest.armor(data);
            var weapon=ThreadedSpikeGameTest.json("weapon_stat_weapon");
            weapon.getAsJsonObject("equipment").getAsJsonArray("items").forEach(v->data.getAsJsonObject("equipment").getAsJsonArray("items").add(v));
            weapon.getAsJsonArray("weapons").forEach(v->data.getAsJsonArray("weapons").add(v));
            weapon.getAsJsonArray("bundles").forEach(v->{if(v.getAsJsonObject().get("id").getAsString().equals("test:surge_empty"))data.getAsJsonArray("bundles").add(v);});
        });
        try {for(int i=0;i<3;i++)unmod(t,"solar_weapon_surge",i);bind(t,"weapon_stat",D+"weapon_stat_damage","");return t;}
        catch(Exception|Error e){t.close();throw e;}
    }
    static void armor(ProjectileGameTest.Harness t,double points){SolarFragmentStatsGameTest.equipArmor(t,"armor/"+UUID.randomUUID(),"arms",points);}
    static String arm(ProjectileGameTest.Harness t,String ammo,String perk) {
        String id="weapon/"+UUID.randomUUID();var stack=new ItemStack(Items.DIAMOND_SWORD);
        stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear(id,"test:stat_"+ammo,Map.of("perk",perk)));
        var equipment=PlayerEquipment.get(t.owner);t.owner.getInventory().setItem(0,stack);equipment.swap(t.owner,"test:primary",0,equipment.revision());
        PugilistGameTest.draw(t,"primary");WeaponSurgeGameTest.aim(t);return id;
    }
    static LivingEntity target(ProjectileGameTest.Harness t,String...tags){var victim=t.cow(2.5,46,3.5);for(String tag:tags)victim.addTag(D+tag);return victim;}
    static void hit(ProjectileGameTest.Harness t,String ammo,double expected,String...tags)throws Exception {
        arm(t,ammo,"none");var victim=target(t,tags);WeaponSurgeGameTest.impact(t,PugilistGameTest.fire(t));
        near(t.h,victim.getHealth(),100-expected,"actual rank-specific weapon damage");near(t.h,t.receipts.getLast().outgoing().orElseThrow().output().value(),expected,"numeric receipt agrees with native HP");victim.discard();
    }
    @GameCase(environment="chorus_gametest:weapon_stat_ranks")
    public void threeAmmoOriginsUsePhysicalArmorAndObservedRanksWithoutGuessingUnknownTargets(GameTestHelper h)throws Exception {
        try(var t=open(h)) {armor(t,150);
            for(String ammo:List.of("primary","special","heavy")) {
                for(String rank:List.of("rank_and_file","elite"))hit(t,ammo,11.5,rank);
                for(String rank:List.of("boss","miniboss","champion"))hit(t,ammo,ammo.equals("heavy")?10.5:10.75,rank);
                hit(t,ammo,10);hit(t,ammo,10,"elite","boss");hit(t,ammo,10,"vehicle");
            }
            h.assertValueEqual(t.hits.size(),24,"all physical contacts completed");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:weapon_stat_snapshot")
    public void inFlightWeaponKeepsLaunchStatAndAmmoButReadsTheVictimsCurrentRank(GameTestHelper h)throws Exception {
        try(var t=open(h)) {armor(t,100);String id=arm(t,"heavy","none");var victim=target(t,"boss");var shot=PugilistGameTest.fire(t);
            armor(t,0);t.runtime.unbind("weapon_stat");arm(t,"primary","none");victim.removeTag(D+"boss");victim.addTag(D+"elite");
            WeaponSurgeGameTest.impact(t,shot);near(h,victim.getHealth(),88.5,"frozen 100 points and original heavy origin select current low-rank bracket");
            h.assertValueEqual(t.hits.getLast().source().weapon(),id,"original weapon survives swapping");victim.discard();hit(t,"primary",10,"elite");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:weapon_stat_fonts",maxTicks=30)
    public void actualFontSurgeRadiantAndRampageComposeAndSpendingOnlyChangesFutureShots(GameTestHelper h)throws Exception {
        var t=open(h);try {armor(t,140);for(int i=0;i<3;i++)mod(t,"weapons_font",i);WeaponSurgeGameTest.surge(t,"solar",3);orb(t);WeaponSurgeGameTest.signal(t,"radiant",10);
            near(h,SolarFragmentStatsGameTest.points(t,"weapons"),190,"three Fonts grant exactly fifty points");String id=arm(t,"primary","rampage");var first=target(t,"boss");first.setHealth(1);
            WeaponSurgeGameTest.impact(t,PugilistGameTest.fire(t));h.assertTrue(first.isDeadOrDying(),"actual kill activates Rampage");first.discard();
            RampageGameTest.at(t,4,()-> {var victim=target(t,"boss");var shot=PugilistGameTest.fire(t);spend(t,1);near(h,SolarFragmentStatsGameTest.points(t,"weapons"),140,"spent shared pool removes Font points");
                WeaponSurgeGameTest.impact(t,shot);near(h,victim.getHealth(),100-10*1.135*1.22*1.2*1.1,"pending shot keeps all captured factors");victim.discard();});
            t.finish(8,()->{try{var victim=target(t,"boss");WeaponSurgeGameTest.impact(t,PugilistGameTest.fire(t));near(h,victim.getHealth(),100-10*1.06*1.2*1.1,"new shot uses base armor and no Surge");h.assertValueEqual(state(t).ammunition().get(id).magazine(),2,"three real ammunition payments");}catch(Exception e){throw new RuntimeException(e);}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:weapon_stat_guardian")
    public void actualGuardianUsesItsOwnBracketEvenInPveAndWithConflictingRankTags(GameTestHelper h)throws Exception {
        try(var t=open(h)) {armor(t,200);arm(t,"special","none");var victim=h.makeMockServerPlayerInLevel();t.entities.add(victim);
            victim.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());victim.getAbilities().invulnerable=false;victim.setInvulnerableTime(0);victim.setNoGravity(true);
            victim.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);victim.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);victim.setHealth(100);
            victim.setPos(h.absoluteVec(new Vec3(2.5,46,3.5)));victim.addTag(D+"boss");victim.addTag(D+"elite");
            WeaponSurgeGameTest.impact(t,PugilistGameTest.fire(t));near(h,victim.getHealth(),89.5,"actual player receives five percent instead of a PvE rank bonus");
            h.assertValueEqual(state(t).mode(),com.imdomestic.chorus.effect.EffectState.Mode.PVE,"recipient classification is independent of activity mode");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:weapon_stat_ignition")
    public void physicalIncandescentRetainsAmmoAndInitialStatForEachIgnitionRecipient(GameTestHelper h)throws Exception {
        try(var t=open(h)) {armor(t,150);var low=cow(t,2,500);low.addTag(D+"elite");var high=cow(t,-2,500);high.addTag(D+"boss");
            kill(t,"primary",false);var original=scorch(t,low).orElseThrow().origin();h.assertTrue(original.tags().contains(D+"primary_ammo"),"physical perk producer carries original ammunition kind");
            armor(t,0);kill(t,"secondary",true);var ignition=hits(t,"ignition");h.assertValueEqual(ignition.size(),4,"both targets reach threshold and each ignition hits both recipients");
            near(h,low.getHealth(),500-4.8-2*67.6*1.15,"low rank uses captured 0-100 points for both explosions");near(h,high.getHealth(),500-4.8-2*67.6*1.075,"boss uses captured enhanced points for both explosions");
            h.assertTrue(ignition.stream().allMatch(d->d.source().equals(original)),"second source cannot replace initial weapon origin");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:weapon_stat_scorch",maxTicks=30)
    public void actualScorchTickKeepsInitialWeaponsStatAfterArmorAndProviderRemoval(GameTestHelper h)throws Exception {
        var t=open(h);try {armor(t,150);var victim=cow(t,2,500);victim.addTag(D+"elite");kill(t,"secondary",true);armor(t,0);t.runtime.unbind("weapon_stat");
            t.finish(11,()->{near(h,victim.getHealth(),497.6-1.353*1.15,"native delayed tick keeps captured armor bonus");h.assertValueEqual(hits(t,"scorch").size(),1,"one actual periodic hit");});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:weapon_stat_unknown")
    public void unknownStatScaledImpactKeepsHealthAndAmmoWithoutReplay(GameTestHelper h)throws Exception {
        try(var t=open(h)) {armor(t,150);String id=arm(t,"heavy","none");var victim=target(t,"boss");t.failAfterDamage=true;var shot=PugilistGameTest.fire(t);
            for(int i=0;i<30&&!shot.isRemoved();i++)shot.tick();h.assertTrue(t.runtime.failure().isPresent(),"unknown native receipt stops execution");
            near(h,victim.getHealth(),89.5,"world damage was committed once");h.assertValueEqual(state(t).ammunition().get(id).magazine(),4,"accepted cost is retained");
            int operations=t.operations.size();shot.tick();t.runtime.prepare();t.runtime.prepare();h.assertValueEqual(t.operations.size(),operations,"unknown impact cannot be replayed");h.assertValueEqual(t.hits.size(),1,"one physical damage operation");
        }h.succeed();
    }
}
