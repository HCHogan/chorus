package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Numerical normalization and contact radius are explicit test calibration, not measured Firesprite values. */
class FirespriteTest {
    static final String ENERGY="chorus_d2:grenade_energy", SLOT="chorus_d2:grenade", COOLDOWN="chorus_d2:firesprite_cooldown";
    static final WorldPosition POINT=new WorldPosition("world",4,40,3);
    static CompiledEffects program() throws Exception {
        return link("firesprite","firesprite_test_calibration","ember_of_tempering","character_stats","weapon_stats","grenade_energy", "arcbolt_energy","tempering_weapon");
    }
    static class Harness {
        final CompiledEffects program; final EffectSession session;
        final List<WorldPickup.Spawn> spawns=new ArrayList<>(); final List<Action.CueCommand> cues=new ArrayList<>(); final List<TargetQuery> selections=new ArrayList<>();
        final List<HealingCommand> heals=new ArrayList<>();
        WorldPickup.Outcome outcome=WorldPickup.Outcome.SPAWNED; boolean failSpawn, omitPosition;int positionReads;Optional<WorldPosition> observedPosition=Optional.of(POINT); List<TargetQuery.Target> allies=List.of();
        Harness() throws Exception { this(program()); }
        Harness(CompiledEffects program) {
            this.program=program;
            var initial=EffectState.empty();
            for(String owner:List.of("player","ally")) {
                initial=initial.withResource(new ResourceState(new ResourceState.Key(owner,ENERGY),0,1,0));
                for(String bundle:List.of("chorus_d2:firesprite_system","test:tempering_inputs"))initial=initial.withSource(source(owner,bundle));
            }
            session=new EffectSession(engine(program),initial,request->switch(request.command()) {
                case PositionQuery q -> {positionReads++;yield new PositionQuery.Result(q,Optional.of(POINT));}
                case TargetQuery q -> { selections.add(q); yield new TargetQuery.Result(q,TargetQuery.Outcome.AVAILABLE,allies.stream().filter(t->!q.exclude().contains(t.entity())).toList()); }
                case WorldPickup.Spawn spawn -> {
                    spawns.add(spawn); if(failSpawn)throw new IllegalStateException("unknown physical creation result");
                    var actual=spawn.position().isEmpty()?WorldPickup.Outcome.MISSING_POSITION:outcome;
                    yield new WorldPickup.Receipt(spawn,actual,actual==WorldPickup.Outcome.SPAWNED?Optional.of("entity/"+spawns.size()):Optional.empty());
                }
                case Action.CueCommand cue -> { cues.add(cue); yield RuleEngine.Empty.INSTANCE; }
                case HealingCommand heal -> { heals.add(heal); yield new HealingReceipt("heal/"+heals.size(),heal,HealingReceipt.Outcome.APPLIED,heal.amount(),heal.amount(),0); }
                default -> throw new AssertionError(request.command());
            });
            for(String owner:List.of("player","ally"))select(owner,"test:grenade");
        }
        static EffectSource source(String owner,String bundle) { return new EffectSource(owner+"/"+bundle,bundle,owner,new BuffInstance.Origin(owner,bundle,"",""),Set.of()); }
        EffectState state(){return session.state().engine().domain();}
        long now(){return state().buffs().timeMicros();}
        double energy(String owner){return state().resources().get(new ResourceState.Key(owner,ENERGY)).value();}
        Optional<BuffInstance> buff(String owner,String id){return state().buffs().instances().values().stream().filter(b->b.key().holder().equals(owner)&&b.definition().id().equals(id)).findFirst();}
        void healthy(){assertTrue(session.state().idle(),()->session.state().engine().failure().toString());}
        void until(long time){session.observe(time,List.of());healthy();}
        void bind(String owner,String bundle){session.start(now(),SourceChange.bind(source(owner,bundle)));healthy();}
        void remove(String owner,String bundle){session.start(now(),SourceChange.remove(source(owner,bundle).instance()));healthy();}
        void select(String owner,String ability){session.start(now(),new AbilityChange(owner,state().abilities().getOrDefault(owner,AbilityLoadout.EMPTY),ability==null?AbilityLoadout.EMPTY:new AbilityLoadout(Map.of(SLOT,ability))).signal());healthy();}
        void event(String kind,String owner,Set<String> tags,Set<String> sourceTags,Map<String,Measure> numbers){
            var event=new EffectEvent(owner,"victim",new BuffInstance.Origin(owner,"shot","weapon","",sourceTags),tags,numbers);
            if(!omitPosition&&(kind.equals("chorus:kill")||kind.equals("chorus_d2:spawn_firesprite")))event=event.withObservedEntities(Optional.of(new EntityObservation(now(),Map.of(),Map.of(new PositionQuery("victim"),observedPosition))));
            session.start(now(),new RuleEngine.Signal(kind,event));healthy();
        }
        void spawn(String owner){event("chorus_d2:spawn_firesprite",owner,Set.of(),Set.of(),Map.of());}
        void stat(String owner,int points){event("test:stat",owner,Set.of(),Set.of(),Map.of("stat",new Measure(points,Unit.STAT_POINT)));}
        void kill(String owner,boolean weapon,boolean solar){event("chorus:kill",owner,weapon?Set.of("chorus:weapon_kill"):Set.of(),solar?Set.of("chorus_d2:solar"):Set.of(),Map.of());}
        void finish(int index,long age,boolean collected){var s=spawns.get(index);session.start(now(),s.finish(new WorldPickup.Contact(collected?WorldPickup.End.COLLECTED:WorldPickup.End.EXPIRED,POINT,collected?Optional.of(s.recipient()):Optional.empty(),age)));healthy();}
        double statQuery(String holder,String stat,double base){return program.calculate(state(),holder,new EffectEvent(holder,holder,new BuffInstance.Origin(holder,"query","",""),Set.of(),Map.of()),"chorus_d2:"+stat,new Measure(base,Unit.STAT_POINT),List.of()).output().value();}
    }
    @Test void cooldownIsSharedAcrossProducersPerRecipientAndReopensAtFiveSeconds() throws Exception {
        var h=new Harness();h.spawn("player");h.spawn("player");h.spawn("ally");
        assertEquals(2,h.spawns.size());assertEquals(5_000_000,h.buff("player",COOLDOWN).orElseThrow().deadline());assertEquals(0,h.energy("player"));
        h.until(4_999_999);h.spawn("player");assertEquals(2,h.spawns.size());
        h.until(5_000_000);h.spawn("player");assertEquals(3,h.spawns.size());assertEquals(10_000_000,h.buff("player",COOLDOWN).orElseThrow().deadline());
    }
    @Test void knownRejectedCreationDoesNotConsumeCooldownWhileUnknownOutcomeCannotBeRetried() throws Exception {
        for(var failure:WorldPickup.Outcome.values())if(failure!=WorldPickup.Outcome.SPAWNED){
            var h=new Harness();h.outcome=failure;h.spawn("player");assertTrue(h.buff("player",COOLDOWN).isEmpty());
            h.outcome=WorldPickup.Outcome.SPAWNED;h.spawn("player");assertTrue(h.buff("player",COOLDOWN).isPresent());assertEquals(0,h.energy("player"));
        }
        var h=new Harness();h.failSpawn=true;assertThrows(IllegalStateException.class,()->h.spawn("player"));
        assertEquals(1,h.spawns.size());assertTrue(h.buff("player",COOLDOWN).isEmpty());assertFalse(h.session.state().engine().pending().isEmpty());
    }
    @Test void collectionUsesCurrentGrenadeStatAfterProducerRemovalAndNeverRewardsAllies() throws Exception {
        var h=new Harness();h.stat("player",0);h.spawn("player");h.stat("player",100);h.stat("ally",0);
        h.remove("player","chorus_d2:firesprite_system");h.finish(0,1,true);
        assertEquals(.05*.75*2.25,h.energy("player"),1e-12);assertEquals(0,h.energy("ally"));
        assertEquals(List.of("player"),h.cues.stream().map(Action.CueCommand::target).toList());
        assertEquals(5_000_000,h.buff("player",COOLDOWN).orElseThrow().deadline(),"collection must not reset generation cooldown");
    }
    @Test void collectionRoutesToCurrentSelectionAndCostlessOrAbsentGrenadesStillConsumeThePickup() throws Exception {
        for(String selected:Arrays.asList(null,"test:costless","test:grenade")){
            var h=new Harness();h.select("player",null);h.spawn("player");h.select("player",selected);h.finish(0,1,true);
            assertEquals("test:grenade".equals(selected)?.0375:0,h.energy("player"),1e-12);assertEquals(1,h.cues.size());
        }
    }
    @Test void twentyFiveSecondExpiryDoesNotRewardOrEmitPickupAndDoesNotRequireSourceToRemain() throws Exception {
        var h=new Harness();h.spawn("player");var spawn=h.spawns.getFirst();
        assertEquals(25_000_000,spawn.parameters().lifetimeMicros());assertEquals(.5,spawn.parameters().radius());
        h.remove("player","chorus_d2:firesprite_system");h.until(25_000_000);double before=h.energy("player");h.finish(0,25_000_000,false);
        assertEquals(before,h.energy("player"));assertTrue(h.cues.isEmpty());assertTrue(h.buff("player",COOLDOWN).isEmpty());
    }
    @Test void fullEnergyStillCollectsAndExcessIsNotTransferredToAnotherPlayer() throws Exception {
        var p=program();var data=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow().getAsJsonObject();
        // A real resource grant tops the recipient up before collecting, without replacing the session.
        var rules=data.getAsJsonArray("bundles");
        for(var b:rules)if(b.getAsJsonObject().get("id").getAsString().equals("test:tempering_inputs"))
            b.getAsJsonObject().getAsJsonArray("rules").add(com.google.gson.JsonParser.parseString("""
                {"id":"fill","on":"test:fill","if":{"type":"chorus:target_is","left":"self","right":"event_actor"},"do":[
                  {"type":"chorus:grant_energy","resource":"chorus_d2:grenade_energy","amount":{"type":"chorus:constant","value":1,"unit":"charge_fraction"},"value_basis":"fixed"}]}
                """));
        var h=new Harness(compile(data));h.event("test:fill","player",Set.of(),Set.of(),Map.of());assertEquals(1,h.energy("player"));
        h.spawn("player");h.finish(0,1,true);assertEquals(1,h.energy("player"));assertEquals(0,h.energy("ally"));assertEquals(1,h.cues.size());
    }
    @Test void spawnUsesRecordedDeathPointAndNeverReadsCurrentCorpsePosition()throws Exception{
        var h=new Harness();var death=new WorldPosition("past:dimension",19,70,-23);h.observedPosition=Optional.of(death);h.spawn("player");
        assertEquals(Optional.of(death),h.spawns.getFirst().position());assertEquals(0,h.positionReads);assertTrue(h.buff("player",COOLDOWN).isPresent());
    }
    @Test void unobservedAndKnownMissingPositionsCannotSpawnOrSpendCooldownButValidLaterRequestsCan()throws Exception{
        for(boolean unknown:List.of(false,true)){
            var h=new Harness();h.omitPosition=unknown;h.observedPosition=Optional.empty();h.spawn("player");
            assertTrue(h.buff("player",COOLDOWN).isEmpty());assertEquals(unknown?0:1,h.spawns.size());assertEquals(0,h.positionReads);
            assertEquals(unknown?List.of("test:firesprite_position_unobserved"):List.of(),h.cues.stream().map(Action.CueCommand::cue).toList());
            h.omitPosition=false;h.observedPosition=Optional.of(POINT);h.spawn("player");assertTrue(h.buff("player",COOLDOWN).isPresent());assertEquals(unknown?1:2,h.spawns.size());
        }
    }
    @Test void radiusAndBaseEnergyCalibrationAreMandatoryAndAllFixturesRoundTrip() throws Exception {
        var p=program();var encoded=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow();
        assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,encoded).getOrThrow());
        for(String id:List.of("chorus_d2:firesprite_collection_radius","chorus_d2:firesprite_base_energy")){
            var data=encoded.deepCopy().getAsJsonObject();var profiles=data.getAsJsonArray("profiles");
            for(int i=profiles.size()-1;i>=0;i--)if(profiles.get(i).getAsJsonObject().get("id").getAsString().equals(id))profiles.remove(i);
            assertThrows(RuntimeException.class,()->compile(data));
        }
    }
}
