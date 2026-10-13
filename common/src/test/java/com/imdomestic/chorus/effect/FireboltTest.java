package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class FireboltTest {
    static final String D = "chorus_d2:", ABILITY = D + "firebolt";
    static CompiledEffects program(boolean calibrated) throws Exception {
        var data = json("firebolt");
        if (calibrated) json("firebolt_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e -> data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value", e.getValue()));
        var base = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, HeatRisesTest.program().program()).getOrThrow().getAsJsonObject();
        base.getAsJsonArray("profiles").asList().removeIf(x -> x.getAsJsonObject().get("id").getAsString().equals(D+"outgoing"));
        for (var profile : json("combat_damage").getAsJsonArray("profiles")) if (profile.getAsJsonObject().get("id").getAsString().equals(D+"outgoing")) base.getAsJsonArray("profiles").add(profile);
        return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, base).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(),
                EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("ability_stat_damage")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("firebolt_energy")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("firebolt_test_calibration").get("program")).getOrThrow()));
    }
    static WorldPosition point(double x) { return new WorldPosition("world", x, 40.6, 0); }
    static class Harness {
        final CompiledEffects p; EffectSession session; int casts;
        final Map<String, WorldPosition> positions = new HashMap<>(); final Map<String, String> lives = new HashMap<>(); final Map<String, Double> hp = new HashMap<>();
        final Set<String> allies = new HashSet<>(Set.of("player", "ally")), players = new HashSet<>(Set.of("player", "guardian")), blocked = new HashSet<>(), denied = new HashSet<>();
        final List<ProjectileFlight.Launch> launches = new ArrayList<>(); final List<TargetQuery> queries = new ArrayList<>();
        final List<DamageCommand> hits = new ArrayList<>(); final List<Double> amounts = new ArrayList<>(); final List<StatusResult.Check> checks = new ArrayList<>();
        boolean failLaunch, failDamage, failStatus, rejectLaunch, noLoss; DamageReceipt.Outcome damageOutcome = DamageReceipt.Outcome.APPLIED;
        Harness() throws Exception { this(EffectState.Mode.PVE); }
        Harness(EffectState.Mode mode) throws Exception {
            p = program(true); session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty().withMode(mode), this::world);
            put("player", -20); put("ally", .5); for (int i=1; i<=6; i++) put("enemy"+i, i);
            choose(ABILITY); bind("test:heat_inputs"); bind(D+"ability_stat_damage");
        }
        void put(String id, double x) { positions.put(id, point(x)); lives.putIfAbsent(id, "first/"+id); hp.putIfAbsent(id, 1000.); }
        boolean alive(String id) { return positions.containsKey(id) && hp.getOrDefault(id, 0.) > 0; }
        EntityAnchor.Reference ref(String id) { return new EntityAnchor.Reference(id, lives.get(id), positions.get(id).dimension()); }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            return switch (r.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.ofNullable(positions.get(q.target())));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 1, 0, 0)));
                case ProjectileFlight.Launch l -> { launches.add(l); if (failLaunch) throw new IllegalStateException("Unknown Firebolt launch"); yield new ProjectileFlight.Receipt(l, rejectLaunch ? ProjectileFlight.Outcome.MISSING_POSITION : ProjectileFlight.Outcome.LAUNCHED, rejectLaunch ? Optional.empty() : Optional.of("flight-"+launches.size())); }
                case EntityAnchor.Query q -> {
                    var outcome = !positions.containsKey(q.target()) ? EntityAnchor.Outcome.MISSING : q.expected().filter(x -> !x.equals(ref(q.target()))).isPresent() ? EntityAnchor.Outcome.REPLACED : EntityAnchor.Outcome.AVAILABLE;
                    yield new EntityAnchor.Result(q, outcome, outcome == EntityAnchor.Outcome.AVAILABLE ? Optional.of(new EntityAnchor.Observation(ref(q.target()), positions.get(q.target()), q.anchor(), alive(q.target()))) : Optional.empty());
                }
                case EntityQuery q -> new EntityQuery.Result(q, !positions.containsKey(q.target()) ? Optional.empty() : Optional.of(new EntityQuery.View(alive(q.target()), players.contains(q.target()), hp.get(q.target()), 1000, 0)));
                case TargetQuery q -> {
                    queries.add(q); var center = q.center() instanceof TargetQuery.PositionCenter pc ? pc.position().orElse(null) : positions.get(((TargetQuery.EntityCenter)q.center()).entity());
                    var list = new ArrayList<TargetQuery.Target>();
                    if (center != null) for (var id : positions.keySet()) if (alive(id) && !q.exclude().contains(id) && !(q.lineOfSight() && blocked.contains(id))) {
                        boolean allied = id.equals(q.relativeTo()) || allies.contains(id) && allies.contains(q.relativeTo()); double distance = center.distanceTo(positions.get(id));
                        if (distance <= q.radius() && (q.relation() == TargetQuery.Relation.ANY || allied == (q.relation() == TargetQuery.Relation.ALLIED))) list.add(new TargetQuery.Target(id, distance));
                    }
                    list.sort(q.comparator()); yield new TargetQuery.Result(q, center == null ? TargetQuery.Outcome.MISSING_CENTER : TargetQuery.Outcome.AVAILABLE, list.stream().limit(q.limit().orElse(Integer.MAX_VALUE)).toList());
                }
                case EntityAnchor.GuardedDamage g -> !alive(g.reference().target()) || !g.reference().equals(ref(g.reference().target()))
                        ? new DamageReceipt(r.id().toString(), DamageReceipt.Outcome.FAILED, 0, 0, 0, Optional.empty(), false) : damage(r, g.damage());
                case DamageCommand c -> damage(r, c);
                case StatusResult.Check q -> { checks.add(q); if (failStatus) throw new IllegalStateException("Unknown Firebolt Scorch authorization"); yield new StatusResult.Checked(q, !alive(q.target()) ? StatusResult.Decision.DEAD : denied.contains(q.target()) ? StatusResult.Decision.DENIED : StatusResult.Decision.ALLOWED); }
                case RelationQuery q -> new RelationQuery.Result(q, Optional.of(allies.contains(q.left()) && allies.contains(q.right())));
                case HealingCommand c -> new HealingReceipt(r.id().toString(), c, HealingReceipt.Outcome.APPLIED, c.amount(), 0, c.amount());
                case Action.CueCommand ignored -> RuleEngine.Empty.INSTANCE;
                default -> throw new AssertionError(r.command());
            };
        }
        DamageReceipt damage(RuleEngine.WorldRequest r, DamageCommand c) {
            hits.add(c); double amount = p.outgoing(state(), c, c.amount()).orElseThrow().output().value(); amounts.add(amount);
            double loss = damageOutcome == DamageReceipt.Outcome.APPLIED && !noLoss ? Math.min(amount, hp.get(c.target())) : 0; hp.compute(c.target(), (k,v) -> v-loss);
            if (failDamage) throw new IllegalStateException("Unknown committed Firebolt damage");
            return new DamageReceipt(r.id().toString(), damageOutcome, 0, 0, loss, hp.get(c.target()) <= 0 ? Optional.of("death/"+r.id()) : Optional.empty(), false);
        }
        EffectState state() { return session.state().engine().domain(); } long now() { return state().buffs().timeMicros(); }
        void send(RuleEngine.Signal s) { session.start(now(), s); } void until(long time) { session.observe(time, List.of()); }
        void bind(String bundle) { send(SourceChange.bind(new EffectSource(bundle, bundle, "player", new BuffInstance.Origin("player", bundle, "", ""), Set.of()))); }
        void touch() { send(SourceChange.bind(new EffectSource("touch", "test:heat_touch", "player", new BuffInstance.Origin("player", "touch", "", ""), Set.of(D+"touch_of_flame")))); }
        void choose(String ability) { var before=state().abilities().getOrDefault("player",AbilityLoadout.EMPTY); var slots=new HashMap<>(before.slots()); if(ability==null)slots.remove(D+"grenade");else slots.put(D+"grenade",ability); send(new AbilityChange("player",before,new AbilityLoadout(slots)).signal()); }
        AbilityUse.Request request() { return new AbilityUse.Request("player",D+"grenade","firebolt/"+ ++casts,new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of())); }
        void cast() { var r=request(); assertEquals(AbilityUse.Outcome.ACCEPTED,((AbilityUse.Receipt)p.useAbility(state(),r).result()).outcome());send(r.signal()); }
        void impact(int index, ProjectileFlight.End end) { var l=launches.get(index); var who=end==ProjectileFlight.End.ARRIVED?l.parameters().destination().map(x -> x.target()):Optional.<String>empty(); send(l.finish(new ProjectileFlight.Impact(end, new WorldPosition("world",0,40,0), who,0,end==ProjectileFlight.End.BLOCK?1:0,0,50_000))); }
        void land() { impact(0,ProjectileFlight.End.BLOCK); } void arrive(int index) { impact(index,ProjectileFlight.End.ARRIVED); }
        void full() { send(new RuleEngine.Signal("test:heat_full",new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of()))); }
        double energy() { return state().resources().get(new ResourceState.Key("player",D+"grenade_energy")).value(); }
        Optional<BuffInstance> buff(String name,String id) { return state().buffs().instances().values().stream().filter(b -> b.key().holder().equals(id)&&b.definition().id().equals(D+name)).findFirst(); }
        List<String> targets() { return launches.stream().skip(1).map(l->l.parameters().destination().orElseThrow().target()).toList(); }
        void restore() { session=EffectSession.restore(p,EffectCheckpointTest.clock(),1,EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())),this::world); }
    }
    @Test void calibratedPaidShellWaitsForPhysicalImpactAndUsesSharedCooldownAndChunkGain() throws Exception {
        var h=new Harness();assertThrows(IllegalArgumentException.class,()->program(false).useAbility(h.state(),h.request()));h.cast();assertEquals(0,h.energy());assertEquals(1,h.launches.size());assertTrue(h.queries.isEmpty()&&h.hits.isEmpty());
        assertEquals(20,h.launches.getFirst().parameters().gravity());assertEquals(.04,BleakWatcherAspectTest.gain(h.p,h.state(),"player",D+"grenade_energy",.04).grant().scaled(),1e-12);
        h.until(1_000_000);assertEquals(1/83.8,h.energy(),1e-12);h.choose(null);h.until(2_000_000);assertEquals(1/83.8,h.energy(),1e-12);
        assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
    }
    @Test void impactSelectsFourVisibleEnemiesAndTheOneSecondDelayNeverRescansOrReplacesThem() throws Exception {
        var h=new Harness();h.blocked.add("enemy2");h.cast();h.land();assertEquals(1,h.queries.size());var q=h.queries.getFirst();assertEquals(8.5,q.radius());assertEquals(OptionalInt.of(4),q.limit());assertTrue(q.lineOfSight());assertEquals(new TargetQuery.PositionCenter(point(0)),q.center());
        h.put("late",.1);h.put("enemy1",30);h.choose(null);h.until(999_999);assertEquals(1,h.launches.size());h.until(1_000_000);assertEquals(List.of("enemy1","enemy3","enemy4","enemy5"),h.targets());assertEquals(1,h.queries.size());
        for(int i=1;i<5;i++)h.arrive(i);assertEquals(4,h.hits.size());assertTrue(h.hits.stream().allMatch(c->c.source().ability().equals(ABILITY)));assertTrue(h.buff("scorch","late").isEmpty());
    }
    @Test void touchAndAshesAreFrozenOnCastAndFiveChosenLivesSurviveDomainRecovery() throws Exception {
        var h=new Harness();h.put("enemy5",12);h.put("enemy6",12.001);h.touch();h.bind(D+"ember_of_ashes");h.cast();h.send(SourceChange.remove("touch"));h.send(SourceChange.remove(D+"ember_of_ashes"));h.land();h.restore();h.until(1_000_000);
        assertEquals(12,h.queries.getFirst().radius());assertEquals(List.of("enemy1","enemy2","enemy3","enemy4","enemy5"),h.targets());h.arrive(5);assertEquals(30,h.buff("scorch","enemy5").orElseThrow().count());
        var late=new Harness();late.cast();late.touch();late.land();late.until(1_000_000);assertEquals(4,late.targets().size());assertEquals(8.5,late.queries.getFirst().radius());
    }
    @Test void missingDeadAndReplacedScanTargetsAreSkippedWithoutFillingVacantSlots() throws Exception {
        var h=new Harness();h.cast();h.land();h.positions.remove("enemy1");h.hp.put("enemy2",0.);h.lives.put("enemy3","replacement");h.until(1_000_000);assertEquals(List.of("enemy4"),h.targets());assertEquals(1,h.queries.size());
        h.lives.put("enemy4","new-life-after-launch");h.arrive(1);assertTrue(h.hits.isEmpty()&&h.checks.isEmpty());
    }
    @Test void secondaryArrivalUsesTargetClassDamageAndSharedScorchWithOriginalGrenadeCredit() throws Exception {
        for(var mode:EffectState.Mode.values())for(boolean guardian:List.of(false,true)){
            var h=new Harness(mode);if(guardian)h.players.add("enemy1");h.cast();h.land();h.until(1_000_000);h.arrive(1);assertEquals(guardian?6.5:33.7,h.amounts.getFirst(),1e-9);var scorch=h.buff("scorch","enemy1").orElseThrow();assertEquals(20,scorch.count());assertEquals(ABILITY,scorch.origin().ability());
            h.until(1_500_000);var dot=h.hits.getLast();assertTrue(dot.tags().contains(D+"scorch_damage")&&dot.tags().contains("chorus:grenade_damage"));assertEquals(ABILITY,dot.source().ability());assertEquals(Set.of("chorus:grenade_kill","chorus:ability_kill"),dot.killTags());
        }
    }
    @Test void immuneLethalAndDeniedHitsDoNotGrantScorchWhileAcceptedZeroLossStillUsesAuthorization() throws Exception {
        for(String kind:List.of("immune","lethal","denied","zero")){
            var h=new Harness();h.cast();h.land();h.until(1_000_000);switch(kind){case "immune"->h.damageOutcome=DamageReceipt.Outcome.IMMUNE;case "lethal"->h.hp.put("enemy1",1.);case "denied"->h.denied.add("enemy1");default->h.noLoss=true;}h.arrive(1);
            assertEquals(kind.equals("zero"),h.buff("scorch","enemy1").isPresent());assertEquals(kind.equals("denied")||kind.equals("zero")?1:0,h.checks.size());
        }
    }
    @Test void shellExpiryAndSecondaryBlockLossOrExpiryCannotInventDamage() throws Exception {
        for(var end:List.of(ProjectileFlight.End.EXPIRED,ProjectileFlight.End.UNLOADED)){var h=new Harness();h.cast();h.impact(0,end);h.until(2_000_000);assertTrue(h.queries.isEmpty()&&h.hits.isEmpty());assertEquals(1,h.launches.size());}
        for(var end:List.of(ProjectileFlight.End.BLOCK,ProjectileFlight.End.TARGET_LOST,ProjectileFlight.End.EXPIRED)){var h=new Harness();h.cast();h.land();h.until(1_000_000);h.impact(1,end);assertTrue(h.hits.isEmpty()&&h.checks.isEmpty());}
    }
    @Test void separatePaidCastsAccumulateTheSharedScorchAndIgniteWithoutAnEngineCycleGuard() throws Exception {
        var h=new Harness();h.bind(D+"ember_of_ashes");
        for(int n=0;n<4;n++){if(n>0)h.full();int shell=h.launches.size();h.cast();h.impact(shell,ProjectileFlight.End.BLOCK);h.until((n+1)*1_000_000L);h.arrive(shell+1);}
        assertTrue(h.buff("scorch","enemy1").isEmpty());assertTrue(h.buff("scorch_lockout","enemy1").isPresent());assertTrue(h.hits.stream().anyMatch(c->c.tags().contains(D+"ignition_damage")&&c.source().ability().equals(ABILITY)));
    }
    @Test void unknownSecondaryLaunchDamageOrScorchNeverReplaysThePaidWorldOperation() throws Exception {
        for(String stage:List.of("launch","damage","status")){
            var h=new Harness();h.cast();h.land();if(stage.equals("launch")){h.failLaunch=true;assertThrows(IllegalStateException.class,()->h.until(1_000_000));assertEquals(2,h.launches.size());}
            else{h.until(1_000_000);if(stage.equals("damage"))h.failDamage=true;else h.failStatus=true;assertThrows(IllegalStateException.class,()->h.arrive(1));assertEquals(1,h.hits.size());assertEquals(966.3,h.hp.get("enemy1"),1e-9);}
            int flights=h.launches.size(),hits=h.hits.size();assertThrows(IllegalStateException.class,()->h.until(3_000_000));assertEquals(flights,h.launches.size());assertEquals(hits,h.hits.size());assertTrue(h.buff("scorch","enemy1").isEmpty());assertTrue(h.energy()<.02);
        }
    }
    @Test void sharedGrenadeStatDamageIsCapturedBeforeTheScanAndSurvivesArmorSourceChanges() throws Exception {
        for(var mode:EffectState.Mode.values()){
            var h=new Harness(mode);h.send(SourceChange.bind(new EffectSource("stats","test:benevolence_stats","player",new BuffInstance.Origin("player","stats","",""),Set.of(),Map.of("grenade",new Measure(200,Unit.STAT_POINT),"melee",new Measure(0,Unit.STAT_POINT),"class_delta",new Measure(0,Unit.DELTA)))));
            h.cast();h.send(SourceChange.remove("stats"));h.land();h.until(1_000_000);h.arrive(1);assertEquals(33.7*(mode==EffectState.Mode.PVE?1.65:1.2),h.amounts.getFirst(),1e-9);
            int shell=h.launches.size();h.full();h.cast();h.impact(shell,ProjectileFlight.End.BLOCK);h.until(2_000_000);h.arrive(shell+1);assertEquals(33.7,h.amounts.getLast(),1e-9);
        }
    }
    @Test void knownShellSpawnRejectionKeepsPaymentAndCannotScheduleAnySeekers() throws Exception {
        var h=new Harness();h.rejectLaunch=true;h.cast();h.until(3_000_000);assertEquals(1,h.launches.size());assertTrue(h.queries.isEmpty()&&h.hits.isEmpty()&&h.checks.isEmpty());assertEquals(3/83.8,h.energy(),1e-12);
    }
}
