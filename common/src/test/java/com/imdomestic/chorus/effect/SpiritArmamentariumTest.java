package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SpiritArmamentariumTest {
    static final String ENERGY="chorus_d2:grenade_energy", SLOT="chorus_d2:grenade", GEAR="chorus_d2:class_item";
    static CompiledEffects program() throws Exception {return link("character_stats","grenade_energy","arcbolt_energy","duskfield_energy","spirit_armamentarium","spirit_armamentarium_inputs");}
    static Loadout gear(String instance) {return instance.isEmpty()?Loadout.EMPTY:new Loadout(Map.of(GEAR,new Loadout.Gear(instance,"test:spirit_class_item",Map.of())),Optional.empty());}
    static EffectSource source(String instance,String bundle,String holder) {return new EffectSource(instance,bundle,holder,new BuffInstance.Origin(holder,instance,"",""),Set.of());}
    static final class Harness {
        final CompiledEffects p; final EffectSession session; final List<HealingCommand> heals=new ArrayList<>(); boolean fail; int casts;
        Harness() throws Exception {
            p=program();session=new EffectSession(engine(p),EffectState.empty(),request->{var heal=(HealingCommand)request.command();heals.add(heal);if(fail)throw new IllegalStateException("Unknown capacity observation");return new HealingReceipt("heal/"+heals.size(),heal,HealingReceipt.Outcome.APPLIED,heal.amount(),heal.amount(),0);});
            send(SourceChange.bind(source("input","test:spirit_inputs","player")));
        }
        EffectState state(){return session.state().engine().domain();}long now(){return state().buffs().timeMicros();}
        void send(RuleEngine.Signal signal){session.start(now(),signal);}void until(long time){session.observe(time,List.of());}
        void select(String holder,String ability){send(new AbilityChange(holder,state().abilities().getOrDefault(holder,AbilityLoadout.EMPTY),ability.isEmpty()?AbilityLoadout.EMPTY:new AbilityLoadout(Map.of(SLOT,"test:"+ability))).signal());}
        void equip(String holder,String instance){send(new EquipmentChange(holder,state().equipment().getOrDefault(holder,Loadout.EMPTY),gear(instance)).signal());}
        ResourceState account(String holder){return state().resources().get(new ResourceState.Key(holder,ENERGY));}
        void grant(double amount){send(new RuleEngine.Signal("test:grant",new EffectEvent("player","player",source("input","test:spirit_inputs","player").origin(),Set.of(),Map.of("amount",new Measure(amount,Unit.CHARGE)))));}
        AbilityUse.Receipt use(){var r=new AbilityUse.Request("player",SLOT,"cast/"+ ++casts,new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of()));var receipt=(AbilityUse.Receipt)p.useAbility(state(),r).result();send(r.signal());return receipt;}
    }
    @Test void equipBeforeSelectionCreatesOneSharedAccountWithoutGrantingTheAddedCharge() throws Exception {
        var h=new Harness();assertTrue(h.state().resources().isEmpty());h.equip("player","first");assertEquals(2,h.account("player").capacity());assertEquals(1,h.account("player").value());
        h.until(1_000_000);assertEquals(1,h.account("player").value(),"empty slot must not regenerate");h.select("player","grenade");assertEquals(1,h.account("player").value());
        assertEquals(AbilityUse.Outcome.ACCEPTED,h.use().outcome());assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,h.use().outcome());assertEquals(1,h.state().resources().size());
    }
    @Test void twoFullChargesPayIndividuallyAndThirdUseIsRejectedWithoutAnExtraBody() throws Exception {
        var h=new Harness();h.select("player","grenade");h.equip("player","first");h.grant(1);assertEquals(2,h.account("player").value());int before=h.heals.size();
        assertEquals(1,h.use().cost().orElseThrow().receipt().paid());assertEquals(1,h.use().cost().orElseThrow().receipt().paid());assertEquals(0,h.account("player").value());
        assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,h.use().outcome());assertEquals(before+2,h.heals.size());
    }
    @Test void samePerkCopiesDoNotStackButIndependentSourceCanCreateThreeCharges() throws Exception {
        var h=new Harness();h.select("player","grenade");h.equip("player","first");h.send(SourceChange.bind(source("duplicate","chorus_d2:spirit_armamentarium","player")));assertEquals(2,h.account("player").capacity());
        h.send(SourceChange.bind(source("aspect","test:additional_charge","player")));assertEquals(3,h.account("player").capacity());h.grant(2);assertEquals(3,h.account("player").value());
        h.equip("player","");assertEquals(3,h.account("player").capacity());assertEquals(3,h.account("player").value());
        h.send(SourceChange.remove("duplicate"));assertEquals(2,h.account("player").capacity());assertEquals(2,h.account("player").value());
        h.send(SourceChange.remove("aspect"));assertEquals(1,h.account("player").value());assertEquals(1,h.account("player").capacity());
    }
    @Test void atomicItemReplacementUsesTheFinalSourceSetWithoutTemporaryClippingOrRefill() throws Exception {
        var h=new Harness();h.select("player","grenade");h.select("other","grenade");h.equip("player","first");h.grant(.7);int heals=h.heals.size();
        h.equip("player","second");assertEquals(1.7,h.account("player").value());assertEquals(2,h.account("player").capacity());assertEquals(heals,h.heals.size());
        h.equip("player","second");assertEquals(1.7,h.account("player").value());assertEquals(1,h.account("other").capacity());h.equip("other","third");assertEquals(2,h.account("other").capacity());
        h.equip("player","");assertEquals(1,h.account("player").value());assertEquals(2,h.account("other").capacity());
    }
    @Test void unequippingWhileTheSlotIsEmptyStillRemovesExcessCapacityAndEnergy() throws Exception {
        var h=new Harness();h.equip("player","first");h.select("player","grenade");h.grant(.7);h.select("player","");assertEquals(1.7,h.account("player").value());
        h.equip("player","");assertEquals(1,h.account("player").capacity());assertEquals(1,h.account("player").value());h.equip("player","second");assertEquals(1,h.account("player").value());
        h.until(1_000_000);assertEquals(1,h.account("player").value());h.select("player","second_grenade");assertEquals(1,h.account("player").value());assertEquals(2,h.account("player").capacity());
    }
    @Test void selectionChangesSplitRechargeWithoutMultiplyingRateOrGrantByCapacity() throws Exception {
        var h=new Harness();h.select("player","grenade");h.use();h.equip("player","first");h.until(500_000);h.select("player","second_grenade");h.until(1_000_000);
        double expected=.5/151.5+.5/131.7;assertEquals(expected,h.account("player").value(),1e-12);h.grant(.04);assertEquals(expected+.04,h.account("player").value(),1e-12);
        h.equip("player","");h.until(1_500_000);assertEquals(expected+.04+.5/131.7,h.account("player").value(),1e-12);
    }
    @Test void unknownWorldObservationRetainsTheEquippedSourceAndNewCapacityWithoutReplay() throws Exception {
        var h=new Harness();h.select("player","grenade");h.fail=true;assertThrows(IllegalStateException.class,()->h.equip("player","first"));
        assertEquals(gear("first"),h.state().equipment().get("player"));assertEquals(2,h.account("player").capacity());assertEquals(1,h.account("player").value());assertEquals(1,h.heals.size());
        assertThrows(IllegalStateException.class,()->h.until(100_000));assertEquals(1,h.heals.size());
    }
    @Test void composedContentRoundTripsAndRequiresTheExplicitResizableAccount() throws Exception {
        var p=program();assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        var data=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow().getAsJsonObject();data.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("resizable",false);
        assertThrows(RuntimeException.class,()->compile(data));
    }
}
