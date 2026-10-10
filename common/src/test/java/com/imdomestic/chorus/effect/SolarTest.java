package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SolarTest {
    static final String SCORCH = "chorus_d2:scorch", LOCKOUT = "chorus_d2:scorch_lockout";
    static EffectSource source(String owner, String credit) { return new EffectSource(owner, "test:scorch_source", owner,
            new BuffInstance.Origin(owner, owner, owner + "-weapon", owner + "-ability", Set.of("chorus:" + credit + "_damage")), Set.of()); }
    static final EffectSource FIRST = source("first", "weapon"), SECOND = source("second", "grenade");
    static CompiledEffects program() throws Exception { return link("solar", "solar_test_calibration", "solar_test_source"); }
    static class Harness {
        final CompiledEffects program; final EffectSession session;
        final Map<String, EntityQuery.View> views = new HashMap<>();
        final List<DamageCommand> damage = new ArrayList<>(); final List<Double> amounts = new ArrayList<>(); final List<Long> times = new ArrayList<>();
        final List<TargetQuery> queries = new ArrayList<>();
        List<TargetQuery.Target> targets = List.of(new TargetQuery.Target("neighbor", 2), new TargetQuery.Target("target", 0));
        StatusResult.Decision decision = StatusResult.Decision.ALLOWED; boolean fail;
        Harness(EffectState.Mode mode) throws Exception { this(program(), mode); }
        Harness(CompiledEffects program, EffectState.Mode mode) {
            this.program = program;
            views.put("target", view(false, false)); views.put("neighbor", view(false, false));
            var state = EffectState.empty().withMode(mode);
            for (var s : List.of(FIRST, SECOND)) state = state.withSource(s).withSource(new EffectSource(s.instance()+"-solar", "chorus_d2:solar_scaling", s.holder(), s.origin(), Set.of()));
            session = new EffectSession(engine(program), state, this::execute);
        }
        RuleEngine.ActionResult execute(RuleEngine.WorldRequest request) {
            return switch (request.command()) {
                case StatusResult.Check q -> new StatusResult.Checked(q, decision);
                case EntityQuery q -> new EntityQuery.Result(q, Optional.ofNullable(views.get(q.target())));
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(new WorldPosition("test:world", 0, 0, 0)));
                case TargetQuery q -> { queries.add(q); yield new TargetQuery.Result(q, TargetQuery.Outcome.AVAILABLE,
                        targets); }
                case DamageCommand cmd -> {
                    damage.add(cmd); times.add(state().buffs().timeMicros());
                    double value = program.outgoing(state(), cmd, cmd.amount()).orElseThrow().output().value(); amounts.add(value);
                    if (fail) throw new IllegalStateException("unknown after Solar damage");
                    yield new DamageReceipt("solar/"+damage.size(), DamageReceipt.Outcome.APPLIED, 0, 0, value, Optional.empty(), false);
                }
                default -> throw new AssertionError(request.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        void healthy() { assertTrue(session.state().engine().failure().isEmpty(), session.state().engine().failure().toString()); assertTrue(session.state().idle()); }
        void apply(long at, EffectSource source, int stacks) { apply(at, source, "target", stacks); }
        void apply(long at, EffectSource source, String target, int stacks) { session.start(at, new RuleEngine.Signal("test:scorch_apply",
                new EffectEvent(source.holder(), target, source.origin(), Set.of(), Map.of("stacks", new Measure(stacks, Unit.COUNT))))); healthy(); }
        void until(long at) { session.observe(at, List.of()); healthy(); }
        Optional<BuffInstance> buff(String id) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(id)).findFirst(); }
        BuffInstance scorch() { return buff(SCORCH).orElseThrow(); }
        void detach(EffectSource source) { for (String id : List.of(source.instance(), source.instance()+"-solar")) session.start(state().buffs().timeMicros(), SourceChange.remove(id)); healthy(); }
    }
    static EntityQuery.View view(boolean player, boolean boss) { return new EntityQuery.View(true, player, 1000, 1000, 0, boss ? Set.of("chorus_d2:boss") : Set.of(), Set.of()); }
    @Test void programRequiresExplicitCalibrationAndRoundTrips() throws Exception {
        assertThrows(IllegalStateException.class, () -> load("solar"));
        var p = program(); assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,p).getOrThrow()).getOrThrow().program());
    }
    @Test void reapplicationSharesStacksAndFirstSourceWithoutRestartingIndependentTickCadence() throws Exception {
        var h = new Harness(EffectState.Mode.PVE); h.apply(0,FIRST,30); long generation=h.scorch().generation();
        var saved=h.scorch().components().damageSnapshots().get("dot").orElseThrow();
        h.apply(200_000,SECOND,30); assertEquals(60,h.scorch().count()); assertEquals(generation,h.scorch().generation());
        assertEquals(FIRST.origin(),h.scorch().origin()); assertSame(saved,h.scorch().components().damageSnapshots().get("dot").orElseThrow());
        h.detach(FIRST); h.until(499_999); assertTrue(h.damage.isEmpty()); h.until(500_000);
        assertEquals(1.6236,h.amounts.getFirst(),1e-9); assertEquals(FIRST.origin(),h.damage.getFirst().source());
        assertEquals(Set.of("chorus:weapon_kill"),h.damage.getFirst().killTags());
        h.until(1_430_000); h.until(1_990_000); assertEquals(List.of(500_000L,1_430_000L,1_990_000L),h.times);
    }
    @Test void bossAndSixtyStackScalingUseCurrentObservedTargetAndCurrentStacks() throws Exception {
        var h = new Harness(EffectState.Mode.PVE); h.views.put("target",view(false,true)); h.apply(0,FIRST,59); h.until(500_000);
        assertEquals((2.7+.175*59)*.1,h.amounts.getFirst(),1e-9);
        h.apply(600_000,SECOND,1); h.views.put("target",view(false,false)); h.until(1_430_000);
        assertEquals(1.6236,h.amounts.getLast(),1e-9);
    }
    @Test void pvpAndPveDecayAreSeparateAndRefreshChangesOnlyDecayDeadline() throws Exception {
        for (var mode:EffectState.Mode.values()) {
            var h=new Harness(mode); h.apply(0,FIRST,2); h.apply(100_000,SECOND,1);
            long decay=mode==EffectState.Mode.PVP?2_400_000:3_100_000;
            long interval=mode==EffectState.Mode.PVP?40_000:100_000;
            h.until(decay-1); assertEquals(3,h.scorch().count()); h.until(decay); assertEquals(2,h.scorch().count());
            h.until(decay+interval); assertEquals(1,h.scorch().count()); h.until(decay+2*interval);
            assertTrue(h.buff(SCORCH).isEmpty()); assertTrue(h.state().timers().isEmpty()); assertTrue(h.queries.isEmpty());
        }
    }
    @Test void thresholdClearsStacksCancelsTicksAndIgnitesWithOriginalCreditThenAllowsNewGeneration() throws Exception {
        var h=new Harness(EffectState.Mode.PVE); h.apply(0,FIRST,60); h.apply(100_000,SECOND,100);
        assertTrue(h.buff(SCORCH).isEmpty()); assertTrue(h.state().timers().isEmpty()); assertEquals(1,h.queries.size());
        assertEquals(2,h.amounts.size()); h.amounts.forEach(v -> assertEquals(67.6,v,1e-9)); assertTrue(h.damage.stream().allMatch(d -> d.source().equals(FIRST.origin())));
        assertTrue(h.damage.stream().allMatch(d -> d.killTags().equals(Set.of("chorus:weapon_kill"))));
        assertEquals(1_700_000,h.buff(LOCKOUT).orElseThrow().deadline()); h.apply(1_699_999,SECOND,30); assertTrue(h.buff(SCORCH).isEmpty());
        h.apply(1_700_000,SECOND,30); assertEquals(SECOND.origin(),h.scorch().origin()); h.until(2_200_000);
        assertEquals(Set.of("chorus:grenade_kill","chorus:ability_kill"),h.damage.getLast().killTags()); assertEquals(SECOND.origin(),h.damage.getLast().source());
    }
    @Test void pvpUsesExplicitInclusiveTickCalibrationNonlethalPolicyAndPerVictimIgnitionFalloff() throws Exception {
        var h=new Harness(EffectState.Mode.PVP); h.views.put("target",view(true,false));
        h.views.put("neighbor",new EntityQuery.View(true,false,1000,1000,0,Set.of("chorus_d2:construct"),Set.of()));
        h.apply(0,FIRST,60); h.until(500_000);
        assertEquals(.7,h.amounts.getFirst(),1e-9); assertTrue(h.damage.getFirst().nonLethal());
        h.apply(600_000,SECOND,40); assertEquals(3,h.amounts.size()); assertEquals(20,h.amounts.get(1),1e-9); assertEquals(12,h.amounts.get(2),1e-9);
        assertTrue(h.damage.subList(1,3).stream().noneMatch(DamageCommand::nonLethal));
    }
    @Test void capturedSolarModifierOutlivesSourceBonusWhileOriginalWeaponCreditSelectsItsEligibility() throws Exception {
        var h=new Harness(EffectState.Mode.PVE);
        h.session.start(0,new RuleEngine.Signal("test:solar_empower",new EffectEvent("first","target",FIRST.origin(),Set.of(),Map.of()))); h.healthy();
        h.apply(0,FIRST,60); h.detach(FIRST); h.until(500_000);
        assertTrue(h.buff("test:solar_bonus").isEmpty()); assertEquals(1.6236*1.5,h.amounts.getFirst(),1e-9);
        h.apply(600_000,SECOND,40); assertEquals(67.6*1.5,h.amounts.getLast(),1e-9);
    }
    @Test void legitimateIgnitionChainsRemainEnabledAndEachTargetsLocalLockoutStopsOnlyItsReapplication() throws Exception {
        var h=new Harness(EffectState.Mode.PVE);
        h.session.start(0,SourceChange.bind(new EffectSource("chain","test:solar_chain",FIRST.holder(),FIRST.origin(),Set.of())));
        h.apply(0,FIRST,100); assertEquals(2,h.queries.size()); assertEquals(4,h.damage.size());
        assertTrue(h.damage.stream().allMatch(d -> d.proc().deny().isEmpty()));
        assertEquals(2,h.state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(LOCKOUT)).count());
        assertTrue(h.state().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals(SCORCH)));
    }
    @Test void missingAndDeadTargetsEndWithoutDamageAndDeniedApplicationsHaveNoState() throws Exception {
        for (boolean dead:List.of(false,true)) {
            var h=new Harness(EffectState.Mode.PVE); h.apply(0,FIRST,30);
            if(dead) h.views.put("target",new EntityQuery.View(false,false,0,100,0)); else h.views.remove("target");
            h.until(500_000); assertTrue(h.damage.isEmpty()); assertTrue(h.buff(SCORCH).isEmpty()); assertTrue(h.state().timers().isEmpty());
        }
        var h=new Harness(EffectState.Mode.PVE); h.decision=StatusResult.Decision.DEAD; h.apply(0,FIRST,100);
        assertTrue(h.buff(SCORCH).isEmpty()); assertTrue(h.buff(LOCKOUT).isEmpty()); assertTrue(h.queries.isEmpty());
    }
    @Test void unknownDamageResultNeverRepeatsTickOrIgnitionAndPreservesCommittedState() throws Exception {
        for(int stacks:List.of(30,100)) {
            var h=new Harness(EffectState.Mode.PVE); h.fail=true;
            if(stacks==30) { h.apply(0,FIRST,stacks); assertThrows(IllegalStateException.class,()->h.until(500_000)); }
            else assertThrows(IllegalStateException.class,()->h.apply(0,FIRST,stacks));
            assertEquals(1,h.damage.size()); assertFalse(h.session.state().idle());
            assertThrows(IllegalStateException.class,()->h.session.observe(1_000_000,List.of())); assertEquals(1,h.damage.size());
            if(stacks==100) { assertTrue(h.buff(SCORCH).isEmpty()); assertTrue(h.buff(LOCKOUT).isPresent()); }
        }
    }
    @Test void severalApplicationsInOneActionFrameProduceOneIgnitionWithoutReinitializingEndedState() throws Exception {
        var h=new Harness(EffectState.Mode.PVE);
        h.session.start(0,new RuleEngine.Signal("test:scorch_double",new EffectEvent(FIRST.holder(),"target",FIRST.origin(),Set.of(),Map.of()))); h.healthy();
        assertEquals(1,h.queries.size()); assertEquals(2,h.damage.size()); assertTrue(h.buff(SCORCH).isEmpty());
    }
    @Test void abilitySourceKindsKeepSpecificAndGenericCreditWithoutInventingWeaponCreditFromIdentity() throws Exception {
        for(String credit:List.of("grenade","melee","super")) {
            var h=new Harness(EffectState.Mode.PVE); var source=source("ability",credit);
            h.session.start(0,SourceChange.bind(source));
            h.session.start(0,SourceChange.bind(new EffectSource("ability-solar","chorus_d2:solar_scaling",source.holder(),source.origin(),Set.of())));
            h.apply(0,source,30); h.until(500_000);
            assertEquals(Set.of("chorus:"+credit+"_kill","chorus:ability_kill"),h.damage.getFirst().killTags());
            assertTrue(h.damage.getFirst().tags().containsAll(Set.of("chorus:"+credit+"_damage","chorus:ability_damage")));
            assertFalse(h.damage.getFirst().tags().contains("chorus:weapon_damage"));
        }
    }
}
