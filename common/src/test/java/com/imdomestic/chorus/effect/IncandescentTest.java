package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real engine equipment/fire/impact receipts; numerical world conversion and falloff are synthetic. */
class IncandescentTest {
    static final List<String> FRAGMENTS=List.of("incandescent","solar","solar_test_calibration","incandescent_test_calibration","ember_of_char","character_stats");
    static final String SCORCH="chorus_d2:scorch";
    static CompiledEffects program(boolean credit) throws Exception { return program(credit,1); }
    static CompiledEffects program(boolean credit,double exemptNonbossFactor) throws Exception {
        var modules=new HashMap<String,ProgramModule>();
        var fixtures=new ArrayList<>(FRAGMENTS);fixtures.add("incandescent_weapon");
        for(String name:fixtures) {
            var data=json(name);
            if(name.equals("solar_test_calibration")) for(var entry:data.getAsJsonArray("profiles")) {
                var profile=entry.getAsJsonObject();
                if(profile.get("id").getAsString().equals("chorus_d2:scorch_nonboss_factor"))
                    profile.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("curve").getAsJsonArray("points").get(1).getAsJsonObject().addProperty("output",exemptNonbossFactor);
            }
            if(!name.equals("incandescent_weapon")) data.addProperty("fragment",true);
            else if(!credit) data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action").add("kill_tags",new JsonArray());
            modules.put("chorus_d2:"+name,ProgramModule.CODEC.parse(JsonOps.INSTANCE,data).getOrThrow());
        }
        return ProgramCatalogue.compile(modules).get("chorus_d2:incandescent_weapon");
    }
    static Loadout loadout(String slot) { return new Loadout(Map.of(
            "test:primary",new Loadout.Gear("a","test:rifle",Map.of("perk","normal")),
            "test:secondary",new Loadout.Gear("b","test:rifle",Map.of("perk","enhanced"))),Optional.of("test:"+slot)); }
    static WorldPosition point(double x) { return new WorldPosition("test:world",x,0,0); }
    static class Harness {
        final CompiledEffects program; final EffectSession session;
        final Map<String,Double> health=new HashMap<>(); final Map<String,WorldPosition> positions=new HashMap<>();
        final Map<String,Set<String>> tags=new HashMap<>(),types=new HashMap<>(); final Set<String> players=new HashSet<>(),missing=new HashSet<>();
        final List<DamageCommand> hits=new ArrayList<>(); final List<Double> amounts=new ArrayList<>(); final List<TargetQuery> queries=new ArrayList<>();
        final List<ProjectileFlight.Launch> shots=new ArrayList<>(); int sequence,checks; boolean unknownStatus,denied,cancelBurst,omitMetadata,omitPosition,missingPosition;
        final List<String> entityReads=new ArrayList<>(),positionReads=new ArrayList<>(),cues=new ArrayList<>();
        java.util.function.BiConsumer<DamageCommand,DamageReceipt> afterReceipt=(_,_) -> {};
        Harness(boolean credit) throws Exception {this(credit,1);}
        Harness(boolean credit,double exemptNonbossFactor) throws Exception {
            program=program(credit,exemptNonbossFactor); session=new EffectSession(engine(program),EffectState.empty(),this::execute);
            entity("player",1000,-10); draw("primary"); bind("solar","chorus_d2:solar_scaling","");bind("inputs","test:projectile","");
        }
        void entity(String id,double hp,double x) { health.put(id,hp);positions.put(id,point(x)); }
        EffectState state(){return session.state().engine().domain();}
        long now(){return state().buffs().timeMicros();}
        void healthy(){assertTrue(session.state().idle());assertTrue(session.state().engine().failure().isEmpty(),session.state().engine().failure().toString());}
        void until(long t){session.observe(t,List.of());healthy();}
        void bind(String id,String bundle,String weapon){session.start(now(),SourceChange.bind(new EffectSource(id,bundle,"player",new BuffInstance.Origin("player",id,weapon,""),Set.of())));healthy();}
        void ashes(){bind("ashes","chorus_d2:ember_of_ashes","");}
        void draw(String slot){session.start(now(),new EquipmentChange("player",state().equipment().getOrDefault("player",Loadout.EMPTY),loadout(slot)).signal());healthy();}
        void fire(){session.start(now(),new WeaponFire.Request("player","shot-"+ ++sequence).signal());healthy();}
        void impact(String target,long time){var launch=shots.getLast();session.start(time,launch.finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,positions.get(target),Optional.of(target),0,0,0,100_000)));healthy();}
        void kill(String target,long time){fire();impact(target,time);assertEquals(0,health.get(target));}
        Optional<BuffInstance> scorch(String target){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(SCORCH)&&b.key().holder().equals(target)).findFirst();}
        List<DamageCommand> bursts(){return hits.stream().filter(c->c.tags().contains("chorus_d2:incandescent_damage")).toList();}
        Optional<EntityQuery.View> view(String target){return missing.contains(target)||!health.containsKey(target)?Optional.empty():Optional.of(new EntityQuery.View(health.get(target)>0,players.contains(target),health.get(target),1000,0,tags.getOrDefault(target,Set.of()),types.getOrDefault(target,Set.of())));}
        DamageReceipt observed(DamageCommand command,DamageReceipt receipt){
            var entities=omitMetadata?Map.<String,Optional<EntityQuery.View>>of():Map.of(command.target(),view(command.target()));
            var points=omitPosition?Map.<PositionQuery,Optional<WorldPosition>>of():Map.of(new PositionQuery(command.target()),missingPosition?Optional.<WorldPosition>empty():Optional.ofNullable(positions.get(command.target())));
            var result=receipt.withObservedEntities(new EntityObservation(now(),entities,points));afterReceipt.accept(command,result);return result;
        }
        RuleEngine.ActionResult execute(RuleEngine.WorldRequest request){return switch(request.command()){
            case PositionQuery q -> {positionReads.add(q.target());yield new PositionQuery.Result(q,Optional.ofNullable(positions.get(q.target())));}
            case DirectionQuery q -> new DirectionQuery.Result(q,Optional.of(new WorldDirection("test:world",1,0,0)));
            case ProjectileFlight.Launch launch -> {shots.add(launch);yield new ProjectileFlight.Receipt(launch,ProjectileFlight.Outcome.LAUNCHED,Optional.of("projectile-"+shots.size()));}
            case EntityQuery q -> {entityReads.add(q.target());yield new EntityQuery.Result(q,view(q.target()));}
            case Action.CueCommand c -> {cues.add(c.cue());yield RuleEngine.Empty.INSTANCE;}
            case TargetQuery q -> {
                queries.add(q); var center=((TargetQuery.PositionCenter)q.center()).position().orElseThrow();
                var targets=positions.entrySet().stream().filter(e->!q.exclude().contains(e.getKey())).map(e->new TargetQuery.Target(e.getKey(),Math.abs(e.getValue().x()-center.x()))).filter(t->t.distance()<=q.radius()).sorted(q.comparator()).toList();
                yield new TargetQuery.Result(q,TargetQuery.Outcome.AVAILABLE,targets);
            }
            case StatusResult.Check q -> { checks++; if(unknownStatus)throw new IllegalStateException("unknown status after Incandescent damage");yield new StatusResult.Checked(q,denied?StatusResult.Decision.DENIED:health.get(q.target())>0?StatusResult.Decision.ALLOWED:StatusResult.Decision.DEAD); }
            case DamageCommand c -> {
                hits.add(c); double amount=program.outgoing(state(),c,c.amount()).orElseThrow().output().value();amounts.add(amount);
                if(cancelBurst&&c.tags().contains("chorus_d2:incandescent_damage"))yield observed(c,new DamageReceipt(request.id().toString(),DamageReceipt.Outcome.IMMUNE,0,0,0,Optional.empty(),false));
                double before=health.get(c.target()),loss=Math.min(before,amount);health.put(c.target(),before-loss);
                yield observed(c,new DamageReceipt(request.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,loss,before>0&&loss==before?Optional.of(request.id()+"/death"):Optional.empty(),false));
            }
            default -> throw new AssertionError(request.command());
        };}
    }
    @Test void allEightDocumentedStackCountsUseActualWeaponKillsAndSelectedEnhancement() throws Exception {
        for(boolean enhanced:List.of(false,true))for(boolean ashes:List.of(false,true))for(boolean strong:List.of(false,true)){
            var h=new Harness(true);if(enhanced)h.draw("secondary");if(ashes)h.ashes();
            h.entity("corpse",5,0);h.entity("near",1000,2);h.entity("far",1000,6);h.entity("outside",1000,8.01);
            if(strong)h.tags.put("corpse",Set.of("chorus_d2:elite"));h.kill("corpse",100_000);
            int expected=strong?(enhanced?(ashes?60:45):(ashes?50:40)):(ashes?(enhanced?45:40):30);
            assertEquals(expected,h.scorch("near").orElseThrow().count());assertEquals(strong,h.scorch("far").isPresent());assertTrue(h.scorch("outside").isEmpty());
            assertEquals(strong?8:4,h.queries.getFirst().radius());assertEquals(strong?2:1,h.bursts().size());
            assertEquals(enhanced?"b":"a",h.scorch("near").orElseThrow().origin().weapon());
            assertEquals(997.6,h.health.get("near"),1e-9);assertEquals(1000,h.health.get("outside"));
        }
    }
    @Test void guardianAndTypeClassificationUseTheStrongBranchAndMissingCorpseSkips() throws Exception {
        for(String kind:List.of("guardian","type","missing")){
            var h=new Harness(true);h.entity("corpse",5,0);h.entity("far",1000,6);
            if(kind.equals("guardian"))h.players.add("corpse");else if(kind.equals("type"))h.types.put("corpse",Set.of("chorus_d2:boss"));else h.missing.add("corpse");
            h.kill("corpse",100_000);assertEquals(!kind.equals("missing"),h.scorch("far").isPresent());
            assertEquals(kind.equals("missing")?0:1,h.queries.size());
        }
    }
    @Test void stowedShotUsesOnlyItsWeaponsPerkWhileUncreditedKillsAndRemovedPerksDoNotTrigger() throws Exception {
        var stowed=new Harness(true);stowed.ashes();stowed.entity("corpse",5,0);stowed.entity("near",1000,2);
        stowed.fire();stowed.draw("secondary");stowed.impact("corpse",100_000);
        assertEquals(40,stowed.scorch("near").orElseThrow().count());assertEquals("a",stowed.bursts().getFirst().source().weapon());
        for(boolean remove:List.of(false,true)){
            var h=new Harness(remove);h.entity("corpse",5,0);h.entity("near",1000,2);h.fire();
            if(remove)h.session.start(h.now(),new EquipmentChange("player",h.state().equipment().get("player"),Loadout.EMPTY).signal());
            h.impact("corpse",100_000);assertEquals(0,h.health.get("corpse"));assertTrue(h.bursts().isEmpty());assertTrue(h.scorch("near").isEmpty());
        }
    }
    @Test void mixedWeaponsReachIgnitionWithFirstSourceAndLockoutStopsScorchButNotBurstDamage() throws Exception {
        var h=new Harness(true);h.ashes();h.entity("first",5,0);h.entity("near",1000,2);h.kill("first",100_000);
        assertEquals(40,h.scorch("near").orElseThrow().count());var origin=h.scorch("near").orElseThrow().origin();
        h.draw("secondary");h.entity("second",5,0);h.tags.put("second",Set.of("chorus_d2:elite"));h.kill("second",200_000);
        assertTrue(h.scorch("near").isEmpty());var ignition=h.hits.stream().filter(c->c.tags().contains("chorus_d2:ignition_damage")).toList();
        assertEquals(1,ignition.size());assertEquals(origin,ignition.getFirst().source());assertEquals(Set.of("chorus:weapon_kill"),ignition.getFirst().killTags());
        h.until(400_000);h.entity("third",5,0);h.kill("third",450_000);
        assertTrue(h.scorch("near").isEmpty());assertEquals(3,h.bursts().size());assertEquals(1000-3*2.4-67.6,h.health.get("near"),1e-9);
    }
    @Test void sourceRankExemptionAndWeaponBonusRemainWithScorchAfterPerkAndBonusAreRemoved() throws Exception {
        var h=new Harness(true);h.ashes();h.bind("bonus","test:incandescent_bonus","b");h.draw("secondary");
        h.entity("corpse",5,0);h.tags.put("corpse",Set.of("chorus_d2:elite"));h.entity("near",1000,2);h.kill("corpse",100_000);
        assertEquals(3.6,h.amounts.getLast(),1e-9);assertEquals(60,h.scorch("near").orElseThrow().count());
        h.session.start(h.now(),SourceChange.remove("bonus"));h.session.start(h.now(),SourceChange.remove("solar"));
        h.session.start(h.now(),new EquipmentChange("player",h.state().equipment().get("player"),Loadout.EMPTY).signal());
        h.until(600_000);assertEquals((2.7+.175*60)*1.025*.1*1.5,h.amounts.getLast(),1e-9);
        assertEquals("b",h.hits.getLast().source().weapon());assertTrue(h.hits.getLast().tags().contains("chorus:weapon_damage"));
    }
    @Test void nonbossScalingForRankExemptScorchIsAnExplicitCalibrationNotAnImplicitTwentyPercentDecision() throws Exception {
        var h=new Harness(true,1.2);h.entity("corpse",5,0);h.entity("near",1000,2);h.kill("corpse",100_000);h.until(600_000);
        assertEquals((2.7+.175*30)*1.2*.1,h.amounts.getLast(),1e-9);
    }
    @Test void deadImmuneAndDeniedNeighborsCannotReceiveScorchAndUnknownStatusDoesNotReplayDamage() throws Exception {
        for(String mode:List.of("dead","immune","denied","unknown")){
            var h=new Harness(true);h.entity("corpse",5,0);h.entity("near",mode.equals("dead")?0:1000,2);
            h.cancelBurst=mode.equals("immune");h.denied=mode.equals("denied");h.unknownStatus=mode.equals("unknown");
            if(h.unknownStatus){assertThrows(IllegalStateException.class,()->h.kill("corpse",100_000));assertEquals(997.6,h.health.get("near"),1e-9);assertThrows(IllegalStateException.class,()->h.until(200_000));assertEquals(2,h.hits.size());}
            else h.kill("corpse",100_000);
            assertTrue(h.scorch("near").isEmpty());if(h.cancelBurst)assertEquals(0,h.checks);
        }
    }
    @Test void weaponCreditedExplosionKillCanTriggerAnotherBurstWithoutARepeatCap() throws Exception {
        var h=new Harness(true);h.entity("corpse",5,0);h.entity("near",1,2);h.entity("far",1000,5);
        h.kill("corpse",100_000);assertEquals(0,h.health.get("near"));assertEquals(2,h.queries.size());
        assertTrue(h.scorch("near").isEmpty());assertEquals(30,h.scorch("far").orElseThrow().count());
        assertTrue(h.bursts().stream().allMatch(c->c.source().weapon().equals("a")&&c.proc().deny().isEmpty()));
    }
    @Test void recordedClassificationAndPositionSurviveRemovalAndOppositeLiveRankWithoutCorpseQueries()throws Exception{
        for(boolean strong:List.of(false,true))for(boolean removed:List.of(false,true)){
            var h=new Harness(true);h.draw("secondary");h.ashes();h.entity("corpse",5,0);h.entity("near",1000,2);h.entity("far",1000,6);
            if(strong)h.tags.put("corpse",Set.of("chorus_d2:elite"));
            h.afterReceipt=(c,r)->{if(c.target().equals("corpse")&&r.lethal()){h.tags.put("corpse",strong?Set.of():Set.of("chorus_d2:boss"));h.positions.put("corpse",point(100));if(removed){h.missing.add("corpse");h.positions.remove("corpse");}}};
            h.kill("corpse",100_000);assertEquals(strong?60:45,h.scorch("near").orElseThrow().count());assertEquals(strong,h.scorch("far").isPresent());
            assertEquals(strong?8:4,h.queries.getFirst().radius());assertEquals(Optional.of(point(0)),((TargetQuery.PositionCenter)h.queries.getFirst().center()).position());
            assertFalse(h.entityReads.contains("corpse"));assertFalse(h.positionReads.contains("corpse"));assertTrue(h.cues.isEmpty());
        }
    }
    @Test void unknownOrUnavailableHistoryReportsGapWithoutUsingCurrentCorpse()throws Exception{
        for(String kind:List.of("metadata_unknown","metadata_unavailable","position_unknown","position_unavailable")){
            var h=new Harness(true);h.entity("corpse",5,0);h.entity("near",1000,2);
            h.omitMetadata=kind.equals("metadata_unknown");h.omitPosition=kind.equals("position_unknown");h.missingPosition=kind.equals("position_unavailable");if(kind.equals("metadata_unavailable"))h.missing.add("corpse");
            h.kill("corpse",100_000);assertTrue(h.queries.isEmpty()&&h.bursts().isEmpty()&&h.scorch("near").isEmpty());assertEquals(List.of("test:incandescent_unresolved"),h.cues);
            assertFalse(h.entityReads.contains("corpse"));assertFalse(h.positionReads.contains("corpse"));
        }
    }
    @Test void eachDerivedLethalBurstUsesItsOwnReceiptPointAfterBothCorpsesAreRemoved()throws Exception{
        var h=new Harness(true);h.entity("corpse",5,0);h.entity("near",1,2);h.entity("far",1000,5);
        h.afterReceipt=(c,r)->{if(r.lethal()){h.missing.add(c.target());h.positions.remove(c.target());}};
        h.kill("corpse",100_000);assertEquals(0,h.health.get("near"));assertEquals(30,h.scorch("far").orElseThrow().count());
        assertEquals(List.of(Optional.of(point(0)),Optional.of(point(2))),h.queries.stream().map(q->((TargetQuery.PositionCenter)q.center()).position()).toList());
        assertTrue(h.bursts().stream().allMatch(c->c.source().weapon().equals("a")&&c.proc().deny().isEmpty()));assertTrue(h.cues.isEmpty());
    }
    @Test void incompleteCalibrationIsRejectedAndTheLinkedProgramRoundTrips() throws Exception {
        assertThrows(IllegalStateException.class,()->load("incandescent"));var p=program(true);
        assertEquals(p.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,p).getOrThrow()).getOrThrow().program());
    }
}
