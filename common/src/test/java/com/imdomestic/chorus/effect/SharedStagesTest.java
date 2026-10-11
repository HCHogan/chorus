package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SharedStagesTest {
    static final EffectSource ATTACK = source("test:attack");
    static CompiledEffects program() throws Exception { return link("damage_snapshot", "shared_stages"); }
    static JsonObject data() throws Exception {
        var d=json("damage_snapshot");var extra=json("shared_stages");
        for(String key:List.of("profiles","bundles"))extra.getAsJsonArray(key).forEach(e->d.getAsJsonArray(key).add(e));return d;
    }
    static EffectState grant(CompiledEffects p,EffectState s,String buff,String holder,int count) {
        return s.withBuffs(Buffs.grant(s.buffs(),p.buff("test:"+buff),holder,holder,ATTACK.origin(),count,1,30_000_000).store());
    }
    static EffectState initial(CompiledEffects p) {
        var s=EffectState.empty().withSource(ATTACK).withResource(new ResourceState(new ResourceState.Key("player","test:power"),.3,1,0));
        return grant(p,s,"empower","player",1);
    }
    static DamageCommand attack(String profile) { return new DamageCommand("target",ATTACK.origin(),10,"test:damage",Set.of(),Set.of(),false,Optional.of("test:"+profile)); }
    static double hit(CompiledEffects p,EffectState s,DamageCommand c) { return p.outgoing(s,c,10).orElseThrow().output().value(); }

    @Test void importedAndLocalProvidersResolveTogetherBeforeTheLocalUnitConversion() throws Exception {
        var p=program();var s=initial(p).withSource(new EffectSource("local","test:local","player",ATTACK.origin(),Set.of()));
        assertEquals(6.75,hit(p,s,attack("derived")),1e-9);
        assertEquals(12,hit(p,s,attack("attack_damage")),1e-9,"sharing does not send local providers back into the parent");
        var r=p.outgoing(s,attack("derived"),10).orElseThrow();
        assertEquals(2,r.trace().contributions().size());assertEquals(1,r.trace().contributions().stream().filter(c->c.selected()).count());
    }
    @Test void sourceOperandsFreezeButVictimConditionsAndOnHitProvidersStayLive() throws Exception {
        var p=program();var saved=p.captureDamage(initial(p),attack("derived"));
        var live=grant(p,EffectState.empty(),"marked","target",1);
        assertEquals(6.75,hit(p,live,saved.command("target")),1e-9);
        assertEquals(6,hit(p,live,saved.command("other")),1e-9);
        live=grant(p,live,"live","player",1);
        assertEquals(7,hit(p,live,saved.command("target")),1e-9);
        live=live.withSource(new EffectSource("victim","test:victim","target",new BuffInstance.Origin("target","victim","",""),Set.of()));
        assertEquals(7.7,hit(p,live,saved.command("target")),1e-9);
        assertEquals(7,hit(p,live,saved.command("other")),1e-9,"provider holder must be the actual victim");
    }
    @Test void nestedChannelsIncludeEachProviderOnceAndDoNotImportParentTransforms() throws Exception {
        var p=program();var s=grant(p,initial(p),"stacked","player",2).withSource(new EffectSource("local","test:local","player",ATTACK.origin(),Set.of()));
        assertEquals(6.75*1.21,hit(p,s,attack("nested")),1e-9);
        var saved=p.captureDamage(s,attack("nested"));
        assertEquals(5,saved.contributions().size());assertEquals(6.75*1.21,hit(p,EffectState.empty(),saved.command("target")),1e-9);
    }
    @Test void sourceWeaponIdentityIsNotReplacedWithTheSharedProfileIdentity() throws Exception {
        var p=program();var s=grant(p,initial(p),"marked","target",1);
        assertEquals(6.75,hit(p,s,attack("derived")),1e-9);
        var c=new DamageCommand("target",new BuffInstance.Origin("player","different","other-weapon",""),10,"test:damage",Set.of(),Set.of(),false,Optional.of("test:derived"));
        assertEquals(6,hit(p,s,c),1e-9);
    }
    @Test void programAndCapturedGraphRoundTripAndResolveAfterSourcesAreRemoved() throws Exception {
        var p=program();var saved=p.captureDamage(initial(p),attack("nested"));
        var restored=EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(saved),DamageSnapshot.class);
        assertEquals(saved,restored);assertEquals(6,hit(p,EffectState.empty(),restored.command("target")),1e-9);
        var decoded=EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow();
        assertEquals(p.program(),decoded);assertEquals(6,hit(new CompiledEffects(decoded),EffectState.empty(),restored.command("target")),1e-9);
    }
    @Test void incompatibleCurrentDestinationLayoutIsRejectedOnlyWhenLiveProvidersParticipate() throws Exception {
        var p=program();var saved=p.captureDamage(initial(p),attack("derived"));var d=data();
        d.getAsJsonArray("profiles").get(2).getAsJsonObject().getAsJsonArray("steps").get(2).getAsJsonObject().getAsJsonObject("curve").getAsJsonArray("coefficients").set(1,new JsonPrimitive(.25));
        var changed=compile(d);
        assertEquals(6,hit(changed,EffectState.empty(),saved.command("target")),1e-9,"frozen layout remains complete");
        assertThrows(IllegalArgumentException.class,()->hit(changed,grant(changed,EffectState.empty(),"live","player",1),saved.command("target")));
    }
    @Test void missingStagesMismatchedGroupsAndCircularAliasesFailDuringLink() throws Exception {
        for(String kind:List.of("missing","layout","cycle")) {
            var d=data();var step=d.getAsJsonArray("profiles").get(2).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject();
            if(kind.equals("missing"))step.addProperty("profile","test:absent");
            else if(kind.equals("layout"))step.getAsJsonObject("apply").getAsJsonObject("group").addProperty("reduction","product");
            else step.addProperty("profile","test:nested");
            var error=assertThrows(IllegalStateException.class,()->compile(d),kind);
            assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains("shared numeric stage"));
        }
    }
    @Test void flatContributionsRequireMatchingUnitsWhileDimensionlessFactorsMayBeShared() throws Exception {
        var dimensionless=data();dimensionless.getAsJsonArray("profiles").get(2).getAsJsonObject().addProperty("input_unit","count");
        assertDoesNotThrow(()->compile(dimensionless),"multipliers have delta units independently of the numeric input");
        var d=json("shared_stages");d.remove("bundles");var profiles=d.getAsJsonArray("profiles");profiles.remove(1);
        var root=profiles.get(0).getAsJsonObject();root.addProperty("input_unit","count");var steps=root.getAsJsonArray("steps");steps.remove(2);steps.remove(1);
        var shared=steps.get(0).getAsJsonObject();shared.addProperty("profile","test:base");shared.getAsJsonObject("apply").addProperty("operation","add");
        var parent=root.deepCopy();parent.addProperty("id","test:base");parent.addProperty("input_unit","damage");var apply=shared.getAsJsonObject("apply").deepCopy();apply.addProperty("type","chorus:apply");var direct=new JsonArray();direct.add(apply);parent.add("steps",direct);profiles.add(parent);
        var invalid=d;assertTrue(assertThrows(IllegalStateException.class,()->compile(invalid)).getMessage().contains("contribution unit differs"));
    }
}
