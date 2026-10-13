package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class HealingGrenadeTest {
    static final String D = "chorus_d2:", ABILITY = D + "healing_grenade", BEHAVIOR = D + "healing_grenade_orb_behavior";
    static CompiledEffects program(boolean calibrated) throws Exception {
        var data = json("healing_grenade");
        if (calibrated) json("healing_grenade_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e -> data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value", e.getValue()));
        return CompiledEffects.link(List.of(HeatRisesTest.program().program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("healing_grenade_energy")).getOrThrow()));
    }
    static WorldPosition point(double x) { return new WorldPosition("world", x, 40, 0); }
    static class Harness {
        final CompiledEffects p; EffectSession session; final Map<String, WorldPosition> positions = new HashMap<>(); final Map<String, Double> hp = new HashMap<>();
        final Map<String, WorldConstruct.Spawn> orbs = new LinkedHashMap<>(); final Map<String, Long> spawnedAt = new HashMap<>();
        final Set<String> removed = new HashSet<>(), allies = new HashSet<>(Set.of("player", "ally", "outside", "late")), denied = new HashSet<>();
        final List<ProjectileFlight.Launch> launches = new ArrayList<>(); final List<WorldConstruct.Consume> claims = new ArrayList<>(); final List<HealingCommand> heals = new ArrayList<>(); final List<StatusResult.Check> checks = new ArrayList<>();
        final List<TargetQuery> queries = new ArrayList<>(); boolean failSpawn, failConsume; String failBuff = ""; WorldConstruct.Outcome rejectSpawn; WorldConstruct.ConsumeOutcome rejectClaim; int cast;
        Harness() throws Exception { this(EffectState.Mode.PVE); }
        Harness(EffectState.Mode mode) throws Exception {
            p = program(true); session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty().withMode(mode), this::world);
            place("player", -10); place("ally", 2); place("outside", 7.501); place("enemy", 1); choose(ABILITY); bind("player", "test:heat_inputs");
        }
        void place(String who, double x) { positions.put(who, point(x)); hp.putIfAbsent(who, 20.); }
        boolean alive(String who) { return positions.containsKey(who) && !removed.contains(who) && hp.getOrDefault(who, 0.) > 0 && (!orbs.containsKey(who) || now() < spawnedAt.get(who) + 7_500_000); }
        WorldPosition at(String who, TargetQuery.Anchor anchor) {
            var pos = positions.get(who); if (pos == null || removed.contains(who)) return null;
            double y = anchor == TargetQuery.Anchor.BODY && orbs.containsKey(who) ? orbs.get(who).parameters().height()/2 : 0;
            return new WorldPosition(pos.dimension(), pos.x(), pos.y() + y, pos.z());
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            return switch (r.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.ofNullable(at(q.target(), q.anchor())));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 1, 0, 0)));
                case ProjectileFlight.Launch launch -> { launches.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("flight-" + launches.size())); }
                case EntityAnchor.Query q -> {
                    var pos = at(q.target(), q.anchor()); var ref = new EntityAnchor.Reference(q.target(), "life/" + q.target(), "world");
                    var outcome = pos == null ? EntityAnchor.Outcome.MISSING : q.expected().filter(e -> !e.equals(ref)).isPresent() ? EntityAnchor.Outcome.REPLACED : EntityAnchor.Outcome.AVAILABLE;
                    yield new EntityAnchor.Result(q, outcome, outcome == EntityAnchor.Outcome.AVAILABLE ? Optional.of(new EntityAnchor.Observation(ref, pos, q.anchor(), alive(q.target()))) : Optional.empty());
                }
                case EntityQuery q -> new EntityQuery.Result(q, positions.containsKey(q.target()) && !removed.contains(q.target()) ? Optional.of(new EntityQuery.View(alive(q.target()), allies.contains(q.target()), hp.get(q.target()), orbs.containsKey(q.target()) ? orbs.get(q.target()).parameters().health() : 100, 0)) : Optional.empty());
                case RelationQuery q -> new RelationQuery.Result(q, positions.containsKey(q.left()) && positions.containsKey(q.right()) ? Optional.of(allies.contains(q.left()) && allies.contains(q.right())) : Optional.empty());
                case TargetQuery q -> {
                    queries.add(q); var center = q.center() instanceof TargetQuery.PositionCenter pc ? pc.position().orElse(null) : at(((TargetQuery.EntityCenter)q.center()).entity(), ((TargetQuery.EntityCenter)q.center()).anchor());
                    var outcome = center == null ? TargetQuery.Outcome.MISSING_CENTER : !positions.containsKey(q.relativeTo()) ? TargetQuery.Outcome.MISSING_RELATIVE : TargetQuery.Outcome.AVAILABLE;
                    var targets = new ArrayList<TargetQuery.Target>();
                    if (outcome == TargetQuery.Outcome.AVAILABLE) for (var id : positions.keySet()) if (alive(id) && !q.exclude().contains(id)) {
                        boolean allied = id.equals(q.relativeTo()) || allies.contains(id) && allies.contains(q.relativeTo());
                        double distance = center.distanceTo(at(id, q.targetAnchor()));
                        if (distance <= q.radius() && (q.relation() == TargetQuery.Relation.ANY || allied == (q.relation() == TargetQuery.Relation.ALLIED))) targets.add(new TargetQuery.Target(id, distance));
                    }
                    targets.sort(q.comparator()); yield new TargetQuery.Result(q, outcome, targets);
                }
                case WorldConstruct.Spawn spawn -> {
                    if (rejectSpawn != null) yield WorldConstruct.Receipt.rejected(spawn, rejectSpawn);
                    String id = "orb-" + (orbs.size() + 1); orbs.put(id, spawn); spawnedAt.put(id, now()); positions.put(id, spawn.position().orElseThrow()); hp.put(id, spawn.parameters().health());
                    if (failSpawn) throw new IllegalStateException("Unknown orb spawn"); yield new WorldConstruct.Receipt(spawn, WorldConstruct.Outcome.SPAWNED, Optional.of(id));
                }
                case WorldConstruct.Consume claim -> {
                    claims.add(claim); var outcome = rejectClaim != null ? rejectClaim : alive(claim.reference().target()) ? WorldConstruct.ConsumeOutcome.CONSUMED : WorldConstruct.ConsumeOutcome.INACTIVE;
                    if (outcome == WorldConstruct.ConsumeOutcome.CONSUMED) removed.add(claim.reference().target());
                    if (failConsume) throw new IllegalStateException("Unknown orb consumption"); yield new WorldConstruct.ConsumeReceipt(claim, outcome);
                }
                case StatusResult.Check check -> {
                    checks.add(check); if (check.definition().id().equals(failBuff)) throw new IllegalStateException("Unknown Restoration permission");
                    yield new StatusResult.Checked(check, denied.contains(check.target()) ? StatusResult.Decision.DENIED : alive(check.target()) ? StatusResult.Decision.ALLOWED : StatusResult.Decision.DEAD);
                }
                case HealingCommand heal -> {
                    heals.add(heal); if (!alive(heal.target())) yield HealingReceipt.unapplied(r.id().toString(), heal, HealingReceipt.Outcome.MISSING);
                    double actual = Math.min(heal.amount(), 100 - hp.get(heal.target())); hp.compute(heal.target(), (key, value) -> value + actual);
                    yield new HealingReceipt(r.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), actual, heal.amount() - actual);
                }
                case Action.CueCommand ignored -> RuleEngine.Empty.INSTANCE;
                default -> throw new AssertionError(r.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); } long now() { return state().buffs().timeMicros(); }
        void send(RuleEngine.Signal signal) { session.start(now(), signal); }
        void until(long time) { session.observe(time, List.of()); }
        void bind(String who, String bundle) { send(SourceChange.bind(new EffectSource(who + "/" + bundle, bundle, who, new BuffInstance.Origin(who, bundle, "", ""), Set.of()))); }
        void touch() { send(SourceChange.bind(new EffectSource("touch", "test:heat_touch", "player", new BuffInstance.Origin("player", "touch", "", ""), Set.of(D + "touch_of_flame")))); }
        void choose(String ability) { var before = state().abilities().getOrDefault("player", AbilityLoadout.EMPTY); var slots = new HashMap<>(before.slots()); if (ability == null) slots.remove(D + "grenade"); else slots.put(D + "grenade", ability); send(new AbilityChange("player", before, new AbilityLoadout(slots)).signal()); }
        AbilityUse.Request request() { return new AbilityUse.Request("player", D + "grenade", "healing/" + ++cast, new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of())); }
        void cast() { send(request().signal()); }
        void land() { land(0, ProjectileFlight.End.BLOCK, 0, 1, 0); }
        void land(int flight, ProjectileFlight.End end, double x, double y, double z) { send(launches.get(flight).finish(new ProjectileFlight.Impact(end, point(0), end == ProjectileFlight.End.ENTITY ? Optional.of("ally") : Optional.empty(), x, y, z, 50_000))); }
        void full() { send(new RuleEngine.Signal("test:heat_full", new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of()))); }
        Optional<BuffInstance> buff(String name, String who) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(D + name) && b.key().holder().equals(who)).findFirst(); }
        double energy() { return state().resources().get(new ResourceState.Key("player", D + "grenade_energy")).value(); }
        List<HealingCommand> cure() { return heals.stream().filter(h -> h.tags().contains(D + "cure")).toList(); }
        void restore() { session = EffectSession.restore(p, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())), this::world); }
    }
    @Test void paidFlightRequiresExplicitCalibrationAndUsesTheRealCooldownAndRecipientScalar() throws Exception {
        var h = new Harness(); assertThrows(IllegalArgumentException.class, () -> program(false).useAbility(h.state(), h.request())); assertEquals(1, h.energy());
        h.cast(); assertEquals(0, h.energy()); assertEquals(1, h.launches.size()); assertTrue(h.heals.isEmpty() && h.orbs.isEmpty());
        var flight = h.launches.getFirst().parameters(); assertEquals(TargetQuery.Relation.ALLIED, flight.tracking().orElseThrow().relation()); assertEquals(720, flight.tracking().orElseThrow().turnRate());
        assertEquals(.035, BleakWatcherAspectTest.gain(h.p, h.state(), "player", D + "grenade_energy", .04).grant().scaled(), 1e-12);
        h.until(1_000_000); assertEquals(1/119.7, h.energy(), 1e-12); h.choose(null); h.until(2_000_000); assertEquals(1/119.7, h.energy(), 1e-12);
        assertEquals(h.p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.p.program()).getOrThrow()).getOrThrow());
    }
    @Test void impactCureAndDelayedOrbRestorationUseSharedDefinitionsInBothModesAndTouchVariants() throws Exception {
        for (var mode : EffectState.Mode.values()) for (boolean touch : List.of(false, true)) {
            var h = new Harness(mode); if (touch) h.touch(); h.bind("ally", D + "ember_of_solace"); h.bind("player", D + "ember_of_benevolence"); h.cast(); h.land();
            assertEquals(1, h.orbs.size()); assertEquals(10, h.orbs.get("orb-1").parameters().health()); assertEquals(7_500_000, h.orbs.get("orb-1").parameters().lifetimeMicros());
            assertTrue(h.buff("restoration", "ally").isEmpty()); h.until(100_000); int tier = touch ? 2 : 1;
            assertEquals(tier, h.buff("restoration", "ally").orElseThrow().tier()); assertEquals(6_050_000, h.buff("restoration", "ally").orElseThrow().deadline());
            assertEquals(2, h.cure().size()); assertTrue(h.cure().stream().allMatch(c -> c.amount() == tier * (mode == EffectState.Mode.PVE ? 3 : 1.5) && c.source().ability().equals(ABILITY)));
            assertEquals(1, h.claims.size()); assertTrue(h.removed.contains("orb-1")); assertTrue(h.buff("healing_grenade_orb_behavior", "orb-1").isEmpty());
            assertTrue(h.buff("benevolence_regeneration", "player").isPresent()); assertEquals(20, h.hp.get("player")); assertEquals(20, h.hp.get("outside")); assertEquals(20, h.hp.get("enemy"));
        }
    }
    @Test void bodyPlacementUsesImpactNormalForFloorWallAndCeilingWithoutMovingTheCureCenter() throws Exception {
        double[][] normals = {{0,1,0},{-1,0,0},{0,-1,0}}; double[][] expected = {{0,40.01,0},{-.26,39.75,0},{0,39.49,0}};
        for (int i=0;i<normals.length;i++) {
            var h = new Harness(); h.cast(); h.land(0, ProjectileFlight.End.BLOCK, normals[i][0], normals[i][1], normals[i][2]); var pos=h.orbs.get("orb-1").position().orElseThrow();
            assertEquals(expected[i][0],pos.x(),1e-12); assertEquals(expected[i][1],pos.y(),1e-12); assertEquals(expected[i][2],pos.z(),1e-12);
            assertEquals(new TargetQuery.PositionCenter(point(0)),h.queries.getFirst().center());
        }
    }
    @Test void firstQualifyingScanConsumesTheSharedOrbForItsCapturedAlliedBatchAndCannotRewardLaterArrivals() throws Exception {
        var h=new Harness(); h.place("player",0); h.place("late",20); h.cast(); h.land(); h.place("outside",1); h.until(50_000);
        for(String who:List.of("player","ally","outside")) assertEquals(1,h.buff("restoration",who).orElseThrow().tier());
        h.place("late",1); h.until(200_000); assertTrue(h.buff("restoration","late").isEmpty()); assertEquals(1,h.claims.size());
    }
    @Test void anUnclaimedOrbWaitsForAnAllyButNeverRewardsAtItsExpiryBoundary() throws Exception {
        for(boolean late:List.of(false,true)){
            var h=new Harness(); h.place("ally",20); h.cast(); h.land(); h.until(late?7_450_000:1_000_000); assertTrue(h.claims.isEmpty());
            if(late){h.place("ally",1);h.until(7_500_000);assertTrue(h.claims.isEmpty());assertTrue(h.buff("restoration","ally").isEmpty());}
            else {h.place("ally",1);h.until(1_050_000);assertEquals(1,h.claims.size());assertEquals(5_050_000,h.buff("restoration","ally").orElseThrow().deadline());assertTrue(h.cure().isEmpty());}
        }
    }
    @Test void touchStrengthIsCapturedAtCastWhileSolaceIsReadFromEachActualCollector() throws Exception {
        var h=new Harness();h.touch();h.cast();h.send(SourceChange.remove("touch"));h.choose(null);h.land();h.bind("ally",D+"ember_of_solace");h.restore();h.until(100_000);
        assertEquals(2,h.buff("restoration","ally").orElseThrow().tier());assertEquals(6_050_000,h.buff("restoration","ally").orElseThrow().deadline());assertTrue(h.cure().stream().allMatch(c->c.amount()==6));
        var base=new Harness();base.cast();base.touch();base.land();base.until(100_000);assertEquals(1,base.buff("restoration","ally").orElseThrow().tier());
    }
    @Test void orbDestructionAndKnownClaimRejectionsDoNotGrantRestoration() throws Exception {
        for(boolean dead:List.of(false,true)){
            var h=new Harness();h.cast();h.land();if(dead)h.hp.put("orb-1",0.);else h.rejectClaim=WorldConstruct.ConsumeOutcome.INACTIVE;
            h.until(100_000);assertTrue(h.buff("restoration","ally").isEmpty());assertEquals(2,h.cure().size());
            if(dead){assertTrue(h.claims.isEmpty());assertTrue(h.buff("healing_grenade_orb_behavior","orb-1").isEmpty());}
        }
    }
    @Test void deniedRecipientAndSharedCureCooldownDoNotRollBackPhysicalOrbOrPayment() throws Exception {
        var h=new Harness();h.denied.add("ally");h.cast();h.land();h.until(100_000);assertEquals(1,h.claims.size());assertTrue(h.cure().isEmpty()&&h.buff("restoration","ally").isEmpty());
        h.denied.clear();h.full();h.cast();h.land(1,ProjectileFlight.End.BLOCK,0,1,0);h.until(200_000);assertEquals(2,h.claims.size());assertEquals(2,h.cure().size());
        h.full();h.cast();h.land(2,ProjectileFlight.End.BLOCK,0,1,0);h.until(300_000);assertEquals(3,h.claims.size());assertEquals(2,h.cure().size());assertTrue(h.energy()<.01);
    }
    @Test void expiryOrUnloadOfTheFlightAndRejectedOrbSpawnNeverInventRestoration() throws Exception {
        for(var end:List.of(ProjectileFlight.End.EXPIRED,ProjectileFlight.End.UNLOADED)){
            var h=new Harness();h.cast();h.land(0,end,0,0,0);assertTrue(h.orbs.isEmpty()&&h.checks.isEmpty());assertEquals(0,h.energy());
        }
        var h=new Harness();h.rejectSpawn=WorldConstruct.Outcome.OBSTRUCTED;h.cast();h.land();h.until(100_000);assertEquals(2,h.cure().size());assertTrue(h.orbs.isEmpty()&&h.claims.isEmpty());
    }
    @Test void unknownSpawnClaimAndRestorationStopWithoutRetryingTheAlreadyCommittedWorldStage() throws Exception {
        for(String stage:List.of("spawn","claim","status")){
            var h=new Harness();h.cast();
            if(stage.equals("spawn")){h.failSpawn=true;assertThrows(IllegalStateException.class,h::land);assertEquals(1,h.orbs.size());}
            else {h.land();if(stage.equals("claim"))h.failConsume=true;else h.failBuff=D+"restoration";assertThrows(IllegalStateException.class,()->h.until(50_000));assertTrue(h.removed.contains("orb-1"));assertEquals(1,h.claims.size());}
            int count=h.claims.size(),spawns=h.orbs.size();assertTrue(h.buff("restoration","ally").isEmpty());assertThrows(IllegalStateException.class,()->h.until(1_000_000));assertEquals(count,h.claims.size());assertEquals(spawns,h.orbs.size());assertTrue(h.energy()<.01);
        }
    }
    @Test void heatRisesConsumesThisActualDefinitionWithoutLaunchingTheGrenadeOrItsOrb() throws Exception {
        for(boolean touch:List.of(false,true)){
            var h=new Harness();h.place("player",0);if(touch)h.touch();h.send(SourceChange.bind(HeatRisesTest.aspect("aspect",60)));
            var event=new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of("chorus:ability_input_release"),Map.of("input_hold_time",new Measure(.3,Unit.SECOND)));
            h.send(new AbilityUse.Request("player",D+"grenade","consume",event).signal());h.until(100_000);
            assertTrue(h.launches.isEmpty()&&h.orbs.isEmpty());assertTrue(h.buff("heat_rises","player").isPresent());assertEquals(6,h.cure().size());assertTrue(h.cure().stream().allMatch(c->c.amount()==9));assertEquals(touch,h.buff("restoration","player").isPresent());assertTrue(h.energy()<.01);
        }
    }
}
