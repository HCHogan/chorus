package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

public class SolarFragmentStatsGameTest {
    static double chunk(double stat){return 1.625-.625*StrictMath.cos(StrictMath.PI*stat/100);}
    static double passive(double stat){return stat>=70?2.10898698+.00639461*stat:1+.004273626*stat+.000300195*stat*stat-6.37618e-7*stat*stat*stat;}
    static void solar(JsonObject data){
        for(String name:List.of("solar","solar_test_calibration","ember_of_char","ember_of_eruption"))
            for(var field:ThreadedSpikeGameTest.json(name).entrySet())if(field.getValue().isJsonArray()){
                if(!data.has(field.getKey()))data.add(field.getKey(),new JsonArray());field.getValue().getAsJsonArray().forEach(v->data.getAsJsonArray(field.getKey()).add(v));
            }
    }
    @GameCase public void physicalFirespriteUsesCollectorsCurrentCharBonusAndKeepsBasePoints(GameTestHelper h)throws Exception {
        for(boolean equippedAtCollection:List.of(false,true))try(var t=FirespriteGameTest.harness(h,SolarFragmentStatsGameTest::solar)){
            DemolitionistGameTest.input(t,"stat",FirespriteGameTest.id(t.owner),"stat",50,Unit.STAT_POINT);
            if(!equippedAtCollection)IncandescentGameTest.bind(t,"char","chorus_d2:ember_of_char","");FirespriteGameTest.pair(t);
            if(equippedAtCollection)IncandescentGameTest.bind(t,"char","chorus_d2:ember_of_char","");else t.runtime.unbind("char");
            double before=FirespriteGameTest.energy(t);var pickup=t.pickups.getFirst();t.owner.setPos(pickup.position());pickup.tick();
            near(h,FirespriteGameTest.energy(t)-before,.05*.75*chunk(equippedAtCollection?60:50),"current Char stat before grenade gain curve and CES");
            h.assertTrue(pickup.isRemoved(),"physical reward consumed");
            near(h,FirespriteGameTest.buff(t,t.owner,"chorus_d2:grenade_stat").orElseThrow().components().numbers().get("points"),50,"base points never rewritten");
            h.assertTrue(t.runtime.failure().isEmpty(),"Char stat pickup failed");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:solar_stat_change",maxTicks=45)
    public void realTickEruptionChangesPassiveAndChunkReturnsAtCurrentMeleeStat(GameTestHelper h)throws Exception {
        var t=new ProjectileGameTest.Harness(h,"threaded_spike",data->{EnergyGainGameTest.prepare(data);solar(data);},true);
        try{
            EnergyGainGameTest.start(t);EnergyGainGameTest.stat(t,60);IncandescentGameTest.bind(t,"eruption","chorus_d2:ember_of_eruption","");
            long start=t.runtime.state().engine().domain().buffs().timeMicros();long[] changed={-1};
            h.runAfterDelay(10,()->{try{t.runtime.unbind("eruption");changed[0]=t.runtime.state().engine().domain().buffs().timeMicros();}catch(Exception|Error e){t.close();throw e;}});
            t.finish(30,()->{
                long end=t.runtime.state().engine().domain().buffs().timeMicros();
                h.assertTrue(changed[0]>start&&changed[0]<end,"fragment removed between real ticks");
                near(h,ThreadedSpikeGameTest.energy(t),((changed[0]-start)*passive(70)+(end-changed[0])*passive(60))/1_000_000/145.2,"fragment change splits old/new passive rates");
                double before=ThreadedSpikeGameTest.energy(t);EnergyGainGameTest.send(t,"base","amount",.04,Unit.CHARGE);near(h,ThreadedSpikeGameTest.energy(t)-before,.04*.8*chunk(60),"unequipped fragment no longer adds points");
                IncandescentGameTest.bind(t,"eruption","chorus_d2:ember_of_eruption","");before=ThreadedSpikeGameTest.energy(t);EnergyGainGameTest.send(t,"base","amount",.04,Unit.CHARGE);near(h,ThreadedSpikeGameTest.energy(t)-before,.04*.8*chunk(70),"current effective melee stat changes actual grant");
                near(h,FirespriteGameTest.buff(t,t.owner,"chorus_d2:melee_stat").orElseThrow().components().numbers().get("points"),60,"raw melee input retained");
            });
        }catch(Exception|Error e){t.close();throw e;}
    }
}
