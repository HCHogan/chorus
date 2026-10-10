package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class BleakWatcherTest {
    static final String ABILITY = "chorus_d2:bleak_watcher", BEHAVIOR = ABILITY + "_behavior", SLOT = "chorus_d2:grenade", ENERGY = ABILITY + "_energy";
    static CompiledEffects program(boolean calibrated) throws Exception {
        var data = json("bleak_watcher");
        if (calibrated) json("bleak_watcher_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e ->
                data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value", e.getValue()));
        var parts = new ArrayList<EffectProgram>(); parts.add(DuranceTest.program().program()); parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
        for (String name : List.of("strand_defense", "bleak_watcher_energy", "bleak_watcher_inputs")) parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json(name)).getOrThrow());
        return CompiledEffects.link(parts);
    }
    static WorldPosition point(double x) { return new WorldPosition("world", x, 40, 0); }
    static class Harness {
        final CompiledEffects p; final EffectSession session;
        final List<ProjectileFlight.Launch> launches = new ArrayList<>(); final List<Long> launchTimes = new ArrayList<>();
        final List<WorldConstruct.Spawn> spawns = new ArrayList<>(); final List<DamageCommand> damage = new ArrayList<>(); final List<StatusResult.Check> checks = new ArrayList<>();
        final Map<String, WorldPosition> positions = new HashMap<>(); final Map<String, EntityQuery.View> entities = new HashMap<>();
        final Set<String> allies = new HashSet<>(); final Set<String> hidden = new HashSet<>();
        ProjectileFlight.Outcome launchOutcome = ProjectileFlight.Outcome.LAUNCHED; WorldConstruct.Outcome spawnOutcome = WorldConstruct.Outcome.SPAWNED;
        boolean failLaunch, failDamage, denySlow; int casts;
        Harness(boolean guardian) throws Exception {
            p = program(true); positions.put("player", point(-10)); positions.put("other", point(100)); positions.put("enemy", point(10));
            entities.put("player", FreezeTest.view(true)); entities.put("other", FreezeTest.view(true)); entities.put("enemy", guardian ? FreezeTest.view(true) : FreezeTest.view(false, "chorus_d2:elite"));
            session = new EffectSession(engine(p), EffectState.empty(), request -> switch (request.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.ofNullable(positions.get(q.target())));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 1, 0, 0)));
                case EntityQuery q -> new EntityQuery.Result(q, Optional.ofNullable(entities.get(q.target())));
                case RelationQuery q -> new RelationQuery.Result(q, positions.containsKey(q.left()) && positions.containsKey(q.right()) ? Optional.of(q.left().equals(q.right()) || allies.contains(q.right())) : Optional.empty());
                case WorldConstruct.Spawn s -> {
                    spawns.add(s); String id = "turret-" + spawns.size();
                    if (spawnOutcome == WorldConstruct.Outcome.SPAWNED) { positions.put(id, s.position().orElseThrow()); entities.put(id, FreezeTest.view(false, "chorus:construct")); }
                    yield new WorldConstruct.Receipt(s, spawnOutcome, spawnOutcome == WorldConstruct.Outcome.SPAWNED ? Optional.of(id) : Optional.empty());
                }
                case ProjectileFlight.Launch l -> {
                    launches.add(l); launchTimes.add(now());
                    if (failLaunch && !l.emitter().equals(l.owner())) throw new IllegalStateException("Unknown turret launch outcome");
                    var outcome = l.emitter().equals(l.owner()) ? ProjectileFlight.Outcome.LAUNCHED : launchOutcome;
                    yield new ProjectileFlight.Receipt(l, outcome, outcome == ProjectileFlight.Outcome.LAUNCHED ? Optional.of("flight-" + launches.size()) : Optional.empty());
                }
                case TargetQuery q -> {
                    var center = ((TargetQuery.PositionCenter) q.center()).position().orElseThrow(); var selected = new ArrayList<TargetQuery.Target>();
                    if (!positions.containsKey(q.relativeTo())) yield new TargetQuery.Result(q, TargetQuery.Outcome.MISSING_RELATIVE, List.of());
                    for (var entry : positions.entrySet()) {
                        String id = entry.getKey(); var view = entities.get(id);
                        if (view == null || !view.alive() || q.exclude().contains(id) || allies.contains(id) || hidden.contains(id)) continue;
                        if (id.startsWith("turret-") && spawns.get(Integer.parseInt(id.substring(7)) - 1).origin().owner().equals(q.relativeTo())) continue;
                        var at = entry.getValue(); double distance = Math.abs(at.x() - center.x());
                        if (distance <= q.radius()) selected.add(new TargetQuery.Target(id, distance));
                    }
                    selected.sort(q.comparator()); if (q.limit().isPresent() && selected.size() > q.limit().getAsInt()) selected.subList(q.limit().getAsInt(), selected.size()).clear();
                    yield new TargetQuery.Result(q, TargetQuery.Outcome.AVAILABLE, selected);
                }
                case DamageCommand d -> {
                    damage.add(d); if (failDamage) throw new IllegalStateException("Unknown turret damage outcome");
                    double amount = p.outgoing(state(), d, d.amount()).map(result -> result.output().value()).orElse(d.amount());
                    yield new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, amount, Optional.empty(), false);
                }
                case StatusResult.Check check -> { checks.add(check); yield new StatusResult.Checked(check, denySlow && check.definition().id().equals("chorus_d2:slow") ? StatusResult.Decision.DENIED : StatusResult.Decision.ALLOWED); }
                case Action.CueCommand ignored -> RuleEngine.Empty.INSTANCE;
                default -> throw new AssertionError(request.command());
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void send(RuleEngine.Signal signal) { session.start(now(), signal); }
        void until(long micros) { session.observe(micros, List.of()); }
        void select(String owner, boolean yes) { send(new AbilityChange(owner, state().abilities().getOrDefault(owner, AbilityLoadout.EMPTY), yes ? new AbilityLoadout(Map.of(SLOT, ABILITY)) : AbilityLoadout.EMPTY).signal()); }
        void cast(String owner) { select(owner, true); send(new AbilityUse.Request(owner, SLOT, "cast-" + ++casts,
                new EffectEvent(owner, owner, new BuffInstance.Origin(owner, "", "", ""), Set.of(), Map.of())).signal()); }
        void land(int flight, double x, long time) { var launch = launches.get(flight); session.start(time, launch.finish(new ProjectileFlight.Impact(ProjectileFlight.End.BLOCK, point(x), Optional.empty(), 0, 1, 0, time - launchTimes.get(flight)))); }
        void hit(int flight, String victim, long time) { var launch = launches.get(flight); session.start(time, launch.finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, positions.get(victim), Optional.of(victim), 0, 0, 0, time - launchTimes.get(flight)))); }
        Optional<BuffInstance> buff(String definition, String holder) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(definition) && b.key().holder().equals(holder)).findFirst(); }
        double incoming(String turret) { return p.defense(state(), new DamageCommand(turret, new BuffInstance.Origin("enemy", "gun", "weapon", ""), 100, "minecraft:generic", Set.of(), Set.of(), false), 100).orElseThrow().output().value(); }
        void durance(String owner) { send(SourceChange.bind(DuranceTest.fragment(owner + "-durance", owner))); }
    }
    @Test void explicitCalibrationIsRequiredBeforeAbilityCostAndDefinitionsRoundTrip() throws Exception {
        var p = program(false); var state = p.changeAbilities(EffectState.empty(), new AbilityChange("player", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of(SLOT, ABILITY)))).state();
        assertThrows(RuntimeException.class, () -> p.useAbility(state, new AbilityUse.Request("player", SLOT, "cast", new EffectEvent("player", "player", new BuffInstance.Origin("player", "", "", ""), Set.of(), Map.of()))));
        var calibrated = program(true).program(); assertEquals(calibrated, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, calibrated).getOrThrow()).getOrThrow());
    }
    @Test void independentConstructHasKnownHealthLifetimeAndFiveShotsPerTwoSecondCycle() throws Exception {
        var h = new Harness(false); h.cast("player"); h.land(0, 0, 100_000);
        assertEquals(150, h.spawns.getFirst().parameters().health()); assertEquals(25_000_000, h.spawns.getFirst().parameters().lifetimeMicros());
        assertEquals(33, h.incoming("turret-1"), 1e-10); h.select("player", false); h.until(1_050_000);
        assertEquals(List.of(0L, 350_000L, 525_000L, 700_000L, 875_000L, 1_050_000L), h.launchTimes);
        assertEquals(100, h.incoming("turret-1")); assertTrue(h.launches.subList(1, 6).stream().allMatch(l -> l.emitter().equals("turret-1") && l.owner().equals("player")));
        h.until(2_350_000); assertEquals(7, h.launches.size()); assertEquals(2_350_000L, h.launchTimes.getLast());
    }
    @Test void rangeVisibilityAllianceAndFailedLaunchPreserveProtectionUntilActualFiring() throws Exception {
        var h = new Harness(false); h.positions.put("enemy", point(35.001)); h.cast("player"); h.land(0, 0, 100_000); h.until(350_000);
        assertEquals(1, h.launches.size()); assertEquals(33, h.incoming("turret-1"), 1e-10);
        h.positions.put("enemy", point(35)); h.hidden.add("enemy"); h.until(525_000); assertEquals(1, h.launches.size());
        h.hidden.clear(); h.allies.add("enemy"); h.until(700_000); assertEquals(1, h.launches.size());
        h.allies.clear(); h.launchOutcome = ProjectileFlight.Outcome.REJECTED; h.until(875_000);
        assertEquals(2, h.launches.size()); assertEquals(33, h.incoming("turret-1"), 1e-10);
        h.launchOutcome = ProjectileFlight.Outcome.LAUNCHED; h.until(1_050_000); assertEquals(100, h.incoming("turret-1"));
        h.positions.remove("player"); h.until(2_350_000); assertEquals(3, h.launches.size());
        assertTrue(h.buff(BEHAVIOR, "turret-1").isPresent());
    }
    @Test void eachActualVictimGetsItsSlowAmountAndHitTimeDuranceCanProduceFreeze() throws Exception {
        for (boolean guardian : List.of(false, true)) {
            var h = new Harness(guardian); h.cast("player"); h.land(0, 0, 100_000); h.until(1_050_000);
            h.durance("player"); h.hit(1, "enemy", 1_100_000);
            var slow = h.buff("chorus_d2:slow", "enemy").orElseThrow(); assertEquals(guardian ? 10 : 20, slow.count());
            assertEquals(guardian ? 3_750_000 : 9_000_000, h.checks.getLast().duration());
            for (int i = 2; i <= 5; i++) h.hit(i, "enemy", 1_100_000 + i * 10_000);
            if (guardian) { assertEquals(50, h.buff("chorus_d2:slow", "enemy").orElseThrow().count()); assertTrue(h.buff("chorus_d2:freeze", "enemy").isEmpty()); }
            else { assertTrue(h.buff("chorus_d2:slow", "enemy").isEmpty()); assertTrue(h.buff("chorus_d2:freeze", "enemy").isPresent()); }
            assertEquals(5, h.damage.size()); assertTrue(h.damage.stream().allMatch(d -> d.source().owner().equals("player") && d.source().ability().equals(ABILITY)
                    && d.tags().contains("chorus:grenade_damage") && d.tags().contains("chorus_d2:stasis") && d.killTags().equals(Set.of("chorus:grenade_kill"))));
        }
    }
    @Test void allianceAtImpactAndMissingOwnerSuppressDamageAndSlowAfterSuccessfulLaunch() throws Exception {
        for (boolean missingOwner : List.of(false, true)) {
            var h = new Harness(false); h.cast("player"); h.land(0, 0, 100_000); h.until(350_000);
            assertEquals(2, h.launches.size()); assertEquals(100, h.incoming("turret-1"));
            if (missingOwner) h.positions.remove("player"); else h.allies.add("enemy");
            h.hit(1, "enemy", 400_000);
            assertTrue(h.damage.isEmpty()); assertTrue(h.checks.isEmpty());
            assertTrue(h.buff("chorus_d2:slow", "enemy").isEmpty());
        }
    }
    @Test void destructionCancelsUnfiredBurstMembersButAlreadyFlyingImpactsKeepTheirParameters() throws Exception {
        var h = new Harness(false); h.cast("player"); h.land(0, 0, 100_000); h.until(600_000); assertEquals(3, h.launches.size());
        h.entities.remove("turret-1"); h.positions.remove("turret-1");
        h.send(new RuleEngine.Signal("chorus:death", new EffectEvent("enemy", "turret-1", new BuffInstance.Origin("enemy", "gun", "weapon", ""), Set.of(), Map.of())));
        h.select("player", false); h.until(1_100_000); assertEquals(3, h.launches.size()); assertTrue(h.buff(BEHAVIOR, "turret-1").isEmpty());
        h.hit(1, "enemy", 1_100_000); h.hit(2, "enemy", 1_110_000);
        assertEquals(40, h.buff("chorus_d2:slow", "enemy").orElseThrow().count()); assertEquals(2, h.damage.size());
        assertTrue(h.damage.stream().allMatch(d -> d.source().equals(h.spawns.getFirst().origin())));
    }
    @Test void removalWithoutDeathObservationStopsAtNextShotAndDeniedSlowDoesNotUndoDamage() throws Exception {
        var h = new Harness(false); h.cast("player"); h.land(0, 0, 100_000); h.until(350_000); h.denySlow = true; h.hit(1, "enemy", 400_000);
        assertEquals(1, h.damage.size()); assertTrue(h.buff("chorus_d2:slow", "enemy").isEmpty());
        h.entities.remove("turret-1"); h.positions.remove("turret-1"); h.until(1_100_000);
        assertEquals(2, h.launches.size()); assertTrue(h.buff(BEHAVIOR, "turret-1").isEmpty()); assertTrue(h.state().timers().isEmpty());
    }
    @Test void twoCastersKeepIndependentTwentyFiveAndThirtySecondLifetimes() throws Exception {
        var h = new Harness(false); h.durance("player"); h.cast("player"); h.cast("other"); h.land(0, 0, 100_000); h.land(1, 40, 100_000);
        assertEquals(30_000_000, h.spawns.getFirst().parameters().lifetimeMicros()); assertEquals(25_000_000, h.spawns.getLast().parameters().lifetimeMicros());
        h.send(SourceChange.remove("player-durance")); h.until(1_100_000);
        assertEquals(12, h.launches.size()); assertEquals(Set.of("player", "other"), new HashSet<>(h.launches.subList(2, 12).stream().map(ProjectileFlight.Launch::owner).toList()));
        h.until(25_100_000); assertTrue(h.buff(BEHAVIOR, "turret-1").isPresent()); assertTrue(h.buff(BEHAVIOR, "turret-2").isEmpty());
        long endedShots = h.launches.stream().filter(l -> l.emitter().equals("turret-2")).count(); assertEquals(65, endedShots);
        h.until(30_100_000); assertTrue(h.buff(BEHAVIOR, "turret-1").isEmpty()); assertTrue(h.state().timers().isEmpty());
        assertEquals(endedShots, h.launches.stream().filter(l -> l.emitter().equals("turret-2")).count());
    }
    @Test void rejectedDeploymentAndUnknownLaunchOrDamageKeepPriorCommitsWithoutReplay() throws Exception {
        var rejected = new Harness(false); rejected.spawnOutcome = WorldConstruct.Outcome.OBSTRUCTED; rejected.cast("player"); rejected.land(0, 0, 100_000); rejected.until(1_100_000);
        assertTrue(rejected.buff(BEHAVIOR, "turret-1").isEmpty()); assertEquals(1, rejected.launches.size());
        for (boolean launch : List.of(false, true)) {
            var h = new Harness(false); h.cast("player"); h.land(0, 0, 100_000); h.failLaunch = launch;
            if (launch) assertThrows(IllegalStateException.class, () -> h.until(350_000));
            else { h.until(350_000); h.failDamage = true; assertThrows(IllegalStateException.class, () -> h.hit(1, "enemy", 400_000)); }
            assertEquals(launch ? 33 : 100, h.incoming("turret-1"), 1e-10); assertEquals(2, h.launches.size()); assertTrue(h.checks.isEmpty());
            assertThrows(IllegalStateException.class, () -> h.until(1_000_000)); assertEquals(2, h.launches.size()); assertEquals(launch ? 0 : 1, h.damage.size());
            assertTrue(h.state().resources().get(new com.imdomestic.chorus.effect.resource.ResourceState.Key("player", ENERGY)).value() < .01);
        }
    }
    @Test void selectedTurretUsesGlacierRechargeAndChunkScalarWithoutResettingEnergyOnReselect() throws Exception {
        var h = new Harness(false); h.cast("player");
        var key = new com.imdomestic.chorus.effect.resource.ResourceState.Key("player", ENERGY); var account = h.state().resources().get(key);
        assertEquals(1 / 175.6, h.p.resourceRate(h.state(), account).perSecond(), 1e-12);
        assertEquals(.04 * .625, h.p.calculate(h.state(), "player", new EffectEvent("player", "player", new BuffInstance.Origin("player", "external", "", ""), Set.of(), Map.of()),
                "chorus_d2:bleak_watcher_gain", new Measure(.04, Unit.CHARGE), List.of()).output().value(), 1e-12);
        var origin = new BuffInstance.Origin("player", "armor", "", "");
        h.send(SourceChange.bind(new EffectSource("armor", "test:bleak_stats", "player", origin, Set.of(), Map.of("points", new Measure(100, Unit.STAT_POINT)))));
        assertEquals(2.75 / 175.6, h.p.resourceRate(h.state(), account).perSecond(), 1e-12);
        assertEquals(.04 * .625 * 2.25, h.p.calculate(h.state(), "player", new EffectEvent("player", "player", origin, Set.of(), Map.of()),
                "chorus_d2:bleak_watcher_gain", new Measure(.04, Unit.CHARGE), List.of()).output().value(), 1e-12);
        h.select("player", false); h.select("player", true); assertEquals(0, h.state().resources().get(key).value());
    }
}
