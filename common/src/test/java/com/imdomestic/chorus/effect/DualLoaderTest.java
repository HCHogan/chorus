package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class DualLoaderTest {
    static JsonObject data() throws Exception {
        var data = IncrementalReloadTest.data();
        IncrementalReloadTest.insert(data).addProperty("rounds_profile", "chorus_d2:reload_insert_rounds");
        data.getAsJsonObject("equipment").getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("sockets").add("loader", json("dual_loader_options"));
        return data;
    }
    static CompiledEffects program(JsonObject data) throws Exception {
        var fragments = new ArrayList<EffectProgram>();
        for (var json : List.of(data, json("kill_clip"), json("reload_insert_rounds"), json("dual_loader"))) {
            // Synthetic weapon and Kill Clip fixtures use an older test version; normalize only this linked harness.
            var normalized = com.google.gson.JsonParser.parseString(json.toString().replace("test-1", "compendium-2026-10-05"));
            fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, normalized).getOrThrow());
        }
        return CompiledEffects.link(fragments);
    }
    static Loadout.Gear gun(String instance, String loader) { return new Loadout.Gear(instance, "test:rifle", Map.of("perk","kill_clip","loader",loader)); }
    static Loadout pair(String drawn, String first, String second) { return new Loadout(Map.of("test:primary",gun("a",first),"test:secondary",gun("b",second)),Optional.of("test:"+drawn)); }
    @Test void normalAndEnhancedAddRoundsPerInsertionWithoutChangingTheAcceptedTiming() throws Exception {
        for (String option : List.of("none","base","enhanced")) {
            var h = new WeaponReloadTest.Harness(program(data()), EffectState.empty()); h.equip(pair("primary",option,"enhanced"));
            var plan = h.reload().plan().orElseThrow(); int expected = option.equals("none") ? 1 : option.equals("base") ? 2 : 3;
            assertEquals(expected,plan.portion().orElseThrow().rounds()); assertEquals(200_000,plan.dueAt());
            h.until(200_000); assertEquals(1+expected,h.ammo("a").magazine()); assertEquals(12-expected,h.ammo("a").reserve().orElseThrow().rounds());
            assertEquals(1,h.ammo("b").magazine()); assertEquals(300_000,h.state().reloads().get("player").dueAt());
            h.until(1_000_000); assertEquals(5,h.ammo("a").magazine()); assertEquals(8,h.ammo("a").reserve().orElseThrow().rounds());
            assertEquals(option.equals("none") ? List.of(1.,1.,1.,1.) : option.equals("base") ? List.of(2.,2.) : List.of(3.,1.),h.heals.stream().map(x->x.amount()).toList());
            assertTrue(h.state().reloads().isEmpty());
        }
    }
    @Test void onlyTheReloadedWeaponAndItsHolderProvideTheExtraRounds() throws Exception {
        var h = new WeaponReloadTest.Harness(program(data()), EffectState.empty()); h.equip(pair("primary","none","enhanced"));
        var other = new Loadout(Map.of("test:primary",gun("other","base")),Optional.of("test:primary"));
        h.session.start(0,new EquipmentChange("other-player",Loadout.EMPTY,other).signal());
        assertEquals(1,h.reload().plan().orElseThrow().portion().orElseThrow().rounds());
        h.equip(pair("secondary","none","enhanced")); assertEquals(3,h.reload().plan().orElseThrow().portion().orElseThrow().rounds());
        var third=(WeaponReload.Receipt)h.program.reload(h.state(),new WeaponReload.Request("other-player","request-other")).result();
        assertEquals(2,third.plan().orElseThrow().portion().orElseThrow().rounds());
    }
    @Test void finalInsertionClipsToReservesAndWholeMagazineReloadRemainsASeparatePolicy() throws Exception {
        var d=data();d.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition").getAsJsonObject("reserves").addProperty("rounds",2);
        var h=new WeaponReloadTest.Harness(program(d),EffectState.empty());h.equip(pair("primary","enhanced","none"));h.reload();h.until(200_000);
        assertEquals(3,h.ammo("a").magazine());assertEquals(0,h.ammo("a").reserve().orElseThrow().rounds());assertTrue(h.state().reloads().isEmpty());
        var full=data();full.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("reload").remove("insert");
        var whole=new WeaponReloadTest.Harness(program(full),EffectState.empty());whole.equip(pair("primary","enhanced","none"));
        assertTrue(whole.reload().plan().orElseThrow().portion().isEmpty());whole.until(200_000);assertEquals(5,whole.ammo("a").magazine());assertEquals(1,whole.heals.size());
        var p=program(data());assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
    }
}
