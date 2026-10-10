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

/** Equipment/fire/reload inputs produce the perk facts; tests never inject kill or reload_finished. */
class VoltshotWeaponTest {
    static final WorldPosition POINT = new WorldPosition("world", 2, 40, 3);
    static CompiledEffects program(boolean credit) throws Exception {
        var modules = new HashMap<String, ProgramModule>();
        for (String name : List.of("voltshot", "jolt", "voltshot_weapon")) {
            var data = json(name);
            if (!name.equals("voltshot_weapon")) data.addProperty("fragment", true);
            else if (!credit) data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action").add("kill_tags", new JsonArray());
            modules.put("chorus_d2:" + name, ProgramModule.CODEC.parse(JsonOps.INSTANCE, data).getOrThrow());
        }
        return ProgramCatalogue.compile(modules).get("chorus_d2:voltshot_weapon");
    }
    static Loadout loadout(String drawn) {
        return new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", "enhanced")),
                "test:secondary", new Loadout.Gear("b", "test:rifle", Map.of("perk", "normal"))), Optional.of("test:" + drawn));
    }
    static final class Harness {
        final CompiledEffects program; final EffectSession session;
        final Map<String, Double> health = new HashMap<>(); final List<ProjectileFlight.Launch> launches = new ArrayList<>();
        final List<DamageCommand> damage = new ArrayList<>(); int sequence, checks; boolean unknownStatus;
        Harness(boolean credit) throws Exception {
            program = program(credit);
            session = new EffectSession(engine(program), EffectState.empty(), request -> switch (request.command()) {
                case PositionQuery query -> new PositionQuery.Result(query, Optional.of(POINT));
                case DirectionQuery query -> new DirectionQuery.Result(query, Optional.of(new WorldDirection("world", 0, 1, 0)));
                case ProjectileFlight.Launch launch -> { launches.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("projectile-" + launches.size())); }
                case WeaponReload.Verify query -> new WeaponReload.Verified(query, true);
                case EntityQuery query -> new EntityQuery.Result(query, Optional.of(new EntityQuery.View(health.getOrDefault(query.target(), 100.0) > 0, false, health.getOrDefault(query.target(), 100.0), 100, 0)));
                case TargetQuery query -> new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, List.of());
                case StatusResult.Check check -> { checks++; if (unknownStatus) throw new IllegalStateException("Unknown Voltshot application"); yield new StatusResult.Checked(check, StatusResult.Decision.ALLOWED); }
                case DamageCommand command -> {
                    damage.add(command); double before = health.getOrDefault(command.target(), 100.0), loss = Math.min(before, command.amount()); health.put(command.target(), before - loss);
                    yield new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, loss, before > 0 && before == loss ? Optional.of(request.id() + "/death") : Optional.empty(), false);
                }
                default -> throw new AssertionError(request.command());
            });
            draw("primary");
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void at(long time) { session.observe(time, List.of()); }
        void draw(String slot) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), loadout(slot)).signal()); }
        WeaponFire.Receipt fire() {
            var request = new WeaponFire.Request("player", "shot-" + ++sequence); var result = (WeaponFire.Receipt) program.fire(state(), request).result(); session.start(now(), request.signal()); return result;
        }
        WeaponReload.Receipt reload() {
            var request = new WeaponReload.Request("player", "reload-" + ++sequence); var result = (WeaponReload.Receipt) program.reload(state(), request).result(); session.start(now(), request.signal()); return result;
        }
        void impact(int index, long time, String target) {
            var launch = launches.get(index);
            session.start(time, launch.finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, POINT, Optional.of(target), 0, 0, 0, 100_000)));
        }
        void kill(String target, long at) { health.put(target, 5.0); fire(); impact(launches.size() - 1, at, target); }
        Optional<BuffInstance> buff(String type, String weapon) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:voltshot_" + type) && b.origin().weapon().equals(weapon)).findFirst(); }
        boolean jolt(String target) { return state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("chorus_d2:jolt") && b.key().holder().equals(target)); }
    }
    @Test void actualReloadCompletionArmsOnlyTheMatchingWeaponWithItsEquippedEnhancement() throws Exception {
        var h = new Harness(true); h.kill("first", 100_000); h.draw("secondary");
        assertEquals(WeaponReload.Outcome.ACCEPTED, h.reload().outcome()); h.at(300_000);
        assertTrue(h.buff("ready", "a").isEmpty() && h.buff("ready", "b").isEmpty());
        h.draw("primary"); h.reload(); h.at(499_999); assertTrue(h.buff("ready", "a").isEmpty()); h.at(500_000);
        assertEquals(8_500_000, h.buff("ready", "a").orElseThrow().deadline()); assertEquals(5, h.state().ammunition().get("a").magazine());
        assertEquals(7, h.state().ammunition().get("a").reserve().orElseThrow().rounds());
        assertEquals(WeaponReload.Outcome.FULL, h.reload().outcome()); assertEquals(8_500_000, h.buff("ready", "a").orElseThrow().deadline());
        h.draw("secondary"); h.kill("second", 600_000); h.reload(); h.at(800_000);
        assertEquals(7_800_000, h.buff("ready", "b").orElseThrow().deadline()); assertEquals(8_500_000, h.buff("ready", "a").orElseThrow().deadline());
    }
    @Test void finishingExactlyAtTheKillWindowBoundaryCannotArmFromAnEarlierReloadStart() throws Exception {
        for (long finish : List.of(5_399_999L, 5_400_000L, 5_400_001L)) {
            var h = new Harness(true); h.kill("first", 100_000); h.at(finish - 200_000); h.reload(); h.at(finish);
            assertEquals(finish < 5_400_000, h.buff("ready", "a").isPresent());
            assertEquals(5, h.state().ammunition().get("a").magazine(), "real refill succeeds even when the perk window expires");
        }
    }
    @Test void stowCancelsReloadButPreservesReadyAndAnAlreadyLaunchedShotsIdentity() throws Exception {
        var h = new Harness(true); h.kill("first", 100_000); h.reload(); h.draw("secondary"); h.at(300_000);
        assertTrue(h.buff("ready", "a").isEmpty()); assertTrue(h.state().reloads().isEmpty()); assertEquals(0, h.state().ammunition().get("a").magazine());
        h.draw("primary"); h.reload(); h.at(500_000); var ready = h.buff("ready", "a").orElseThrow();
        var shot = h.fire().shot().orElseThrow(); h.draw("secondary"); assertEquals(ready, h.buff("ready", "a").orElseThrow());
        h.impact(1, 600_000, "target"); assertTrue(h.jolt("target")); assertTrue(h.buff("ready", "a").isEmpty());
        assertEquals(shot.origin(), h.damage.getLast().source()); assertEquals(1, h.state().ammunition().get("b").magazine());
    }
    @Test void physicalWeaponProvenanceWithoutKillCreditCannotArmAfterARealRefill() throws Exception {
        var h = new Harness(false); h.kill("first", 100_000); assertEquals(0, h.health.get("first")); assertEquals("a", h.damage.getFirst().source().weapon());
        assertTrue(h.buff("window", "a").isEmpty()); h.reload(); h.at(300_000);
        assertEquals(5, h.state().ammunition().get("a").magazine()); assertTrue(h.buff("ready", "a").isEmpty());
    }
    @Test void unknownStatusResultRetainsTheSpentReadyChargeAndDoesNotReplayItsWorldRequest() throws Exception {
        var h = new Harness(true); h.kill("first", 100_000); h.reload(); h.at(300_000); h.fire(); h.unknownStatus = true;
        assertThrows(IllegalStateException.class, () -> h.impact(1, 400_000, "target"));
        assertEquals(90, h.health.get("target")); assertTrue(h.buff("ready", "a").isEmpty()); assertFalse(h.jolt("target"));
        assertTrue(h.session.state().engine().pending().isPresent()); assertEquals(1, h.checks); assertEquals(2, h.damage.size());
        h.unknownStatus = false; assertThrows(IllegalStateException.class, () -> h.at(500_000)); assertEquals(1, h.checks);
    }
}
