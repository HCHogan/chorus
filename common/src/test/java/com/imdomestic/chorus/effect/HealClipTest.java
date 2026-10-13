package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class HealClipTest {
    static final String D = "chorus_d2:";
    static Map<String, Measure> calibration() {
        // Both windows are explicit synthetic test inputs: the original normal value has '?' and enhanced is unknown.
        return Map.of("window_seconds", new Measure(5.3, Unit.SECOND), "enhanced_window_seconds", new Measure(6.3, Unit.SECOND), "receipt_seconds", new Measure(60, Unit.SECOND));
    }
    static CompiledEffects program(boolean incremental) throws Exception {
        var weapon = json("heal_clip_weapon");
        if (incremental) {
            var insert = json("incremental_reload_settings").getAsJsonObject("insert");
            insert.getAsJsonObject("repeat").getAsJsonObject("value").addProperty("value", 1.2);
            weapon.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("reload").add("insert", insert);
        }
        return CompiledEffects.link(List.of(BenevolenceTest.program().program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("heal_clip")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, weapon).getOrThrow()));
    }
    static class Harness extends BenevolenceTest.Harness {
        final List<ProjectileFlight.Launch> flights = new ArrayList<>(); final List<RuleEngine.Signal> kills = new ArrayList<>();
        final List<EffectEvent> reloads = new ArrayList<>(); boolean reloadAllowed = true, lethal = true;
        boolean noAllies; int sequence;
        Harness() throws Exception { this(false, EffectState.Mode.PVE); }
        Harness(boolean incremental, EffectState.Mode mode) throws Exception { super(program(incremental), mode); equip("primary", "normal", "enhanced"); bind("player", "test:heal_clip_inputs"); }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            return switch (r.command()) {
                case WeaponReload.Verify q -> new WeaponReload.Verified(q, reloadAllowed);
                case InstantReload.Check q -> {
                    var reply = new InstantReload.Checked(q, reloadAllowed);
                    for (var fact : p.completeInstantReload(state(), reply).emitted()) if (fact.type().equals("chorus:reload_finished")) reloads.add(((InstantReload.Completed)fact.payload()).event());
                    yield reply;
                }
                case Action.CueCommand ignored -> RuleEngine.Empty.INSTANCE;
                case ProjectileFlight.Launch q -> { flights.add(q); yield new ProjectileFlight.Receipt(q, ProjectileFlight.Outcome.LAUNCHED, Optional.of("flight/" + flights.size())); }
                case DamageCommand c -> {
                    var receipt = new DamageReceipt(r.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, c.amount(), lethal ? Optional.of("death/" + r.id()) : Optional.empty(), false);
                    kills.addAll(DamageFacts.from(c, receipt).stream().filter(s -> s.type().equals("chorus:kill")).toList()); yield receipt;
                }
                case TargetQuery q -> {
                    if (q.relation() != TargetQuery.Relation.ALLIED || q.radius() != 15) yield super.world(r);
                    queries.add(q); yield new TargetQuery.Result(q, TargetQuery.Outcome.AVAILABLE, noAllies ? List.of() : List.of(new TargetQuery.Target("ally", 15)));
                }
                default -> super.world(r);
            };
        }
        Loadout.Gear gun(String id, String variant) { return new Loadout.Gear(id, "test:rifle", Map.of("perk", variant), calibration()); }
        void equip(String drawn, String a, String b) {
            send(new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), new Loadout(Map.of("test:primary", gun("a", a), "test:secondary", gun("b", b)), Optional.of("test:" + drawn))).signal());
        }
        void fire() {
            send(new WeaponFire.Request("player", "shot/" + ++sequence).signal());
            var flight = flights.getLast(); send(flight.finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, WeaponFireTest.POINT, Optional.of("enemy"), 0, 0, 0, 0)));
        }
        void kill(String weapon, String id) {
            var c = new DamageCommand("enemy", new BuffInstance.Origin("player", "indirect", weapon, ""), 1, "minecraft:generic", Set.of("chorus:weapon_damage"), Set.of("chorus:weapon_kill"), false);
            var facts = DamageFacts.from(c, new DamageReceipt("damage/" + id, DamageReceipt.Outcome.APPLIED, 0, 0, 1, Optional.of(id), false));
            kills.addAll(facts.stream().filter(s -> s.type().equals("chorus:kill")).toList()); session.observe(now(), facts);
        }
        void reload() { send(new WeaponReload.Request("player", "reload/" + ++sequence).signal()); }
        void instant() {
            send(new RuleEngine.Signal("test:heal_clip_instant", new EffectEvent("player", "player", new BuffInstance.Origin("player", "skill", "", "test:skill"), Set.of(), Map.of())));
        }
        void rounds(String type, String weapon, int n) { send(new RuleEngine.Signal("test:" + type, new EffectEvent("player", "", new BuffInstance.Origin("player", "input", weapon, ""), Set.of(), Map.of("rounds", new Measure(n, Unit.ROUND))))); }
        Optional<BuffInstance> window(String weapon) { return state().buffs().instances().values().stream().filter(b -> b.key().definition().equals(D + "heal_clip_window") && b.origin().weapon().equals(weapon)).findFirst(); }
        List<HealingCommand> cure(String who) { return heals.stream().filter(c -> c.tags().contains(D + "cure") && c.target().equals(who)).toList(); }
        double healed(String who) { return cure(who).stream().mapToDouble(HealingCommand::amount).sum(); }
    }
    @Test void confirmedWeaponShotManualReloadAndSharedCureTriggerTheActualGiversBenevolence() throws Exception {
        var h = new Harness(); h.fire(); assertTrue(h.window("a").isPresent()); assertTrue(h.window("b").isEmpty());
        h.reload(); h.until(199_999); assertEquals(0, h.healed("player")); h.until(200_000);
        assertEquals(5, h.state().ammunition().get("a").magazine()); assertTrue(h.window("a").isEmpty()); assertTrue(h.benefit().isEmpty());
        h.until(300_000); assertEquals(12, h.healed("player")); assertEquals(6, h.healed("ally")); assertEquals(7_300_000, h.benefit().orElseThrow().deadline());
        assertEquals("a", h.cure("ally").getFirst().source().weapon()); assertEquals("player", h.cure("ally").getFirst().source().owner());
        assertEquals(15, h.queries.getLast().radius()); assertFalse(h.queries.getLast().lineOfSight());
        assertEquals(h.p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.p.program()).getOrThrow()).getOrThrow());
    }
    @Test void modeChangesSharedCureAmountsAndSelfOnlyDoesNotTriggerBenevolence() throws Exception {
        var h = new Harness(false, EffectState.Mode.PVP); h.noAllies = true; h.fire(); h.reload(); h.until(300_000);
        assertEquals(6, h.healed("player")); assertEquals(0, h.healed("ally")); assertTrue(h.benefit().isEmpty());
    }
    @Test void weaponReloadHealingAcceleratesAllThreeCurrentReceivingAccountsOnlyAfterConfirmedCure() throws Exception {
        var h = new Harness(); h.stats(110, 100, 1); h.drain(); h.fire(); h.reload(); h.until(1_000_000);
        assertEquals(.0275 + .03 * .75, h.energy("grenade_energy"), 1e-10);
        assertEquals(.0275 + .03 * .75, h.energy("solar_melee_progress"), 1e-10);
        assertEquals((2 + 3 * .75) / 56, h.energy(), 1e-10);
    }
    @Test void eachWeaponRequiresItsOwnKillAndOnlyAConfirmedLethalWeaponCreditOpensTheWindow() throws Exception {
        var h = new Harness(); h.lethal = false; h.fire(); assertTrue(h.window("a").isEmpty()); h.reload(); h.until(300_000); assertEquals(0, h.healed("player"));
        h.kill("b", "b-kill"); h.rounds("spend", "a", 1); h.reload(); h.until(600_000); assertTrue(h.window("b").isPresent()); assertEquals(0, h.healed("player"));
        h.equip("secondary", "normal", "enhanced"); h.reload(); h.until(900_000); assertEquals(12, h.healed("player"));
    }
    @Test void ordinaryRefillAndRejectedCompletionPreserveTheWindowWithoutHealing() throws Exception {
        var h = new Harness(); h.kill("a", "first"); h.rounds("refill", "a", 0); h.until(100_000); assertTrue(h.window("a").isPresent()); assertEquals(0, h.healed("player"));
        h.rounds("spend", "a", 1); h.reloadAllowed = false; h.reload(); h.until(400_000); assertTrue(h.window("a").isPresent()); assertEquals(0, h.healed("player"));
    }
    @Test void completionTimeAndHalfOpenExpiryApplyToExplicitNormalAndEnhancedWindows() throws Exception {
        for (boolean enhanced : List.of(false, true)) for (boolean atBoundary : List.of(false, true)) {
            var h = new Harness(); String weapon = enhanced ? "b" : "a"; h.equip(enhanced ? "secondary" : "primary", "normal", "enhanced");
            h.kill(weapon, "kill"); long end = enhanced ? 6_300_000 : 5_300_000;
            assertEquals(end, h.window(weapon).orElseThrow().deadline()); h.until(end - 200_000 - (atBoundary ? 0 : 1)); h.reload(); h.until(end + 100_000);
            assertEquals(atBoundary ? 0 : 12, h.healed("player"));
        }
    }
    @Test void duplicateDeathsCannotReopenAConsumedWindowAndOldCompletionsCannotConsumeANewKill() throws Exception {
        var h = new Harness(); h.kill("a", "first"); var kill = h.kills.getLast(); h.instant(); var old = h.reloads.getFirst(); h.until(100_000); assertEquals(12, h.healed("player"));
        h.send(kill); assertTrue(h.window("a").isEmpty()); h.until(1_200_000); h.kill("a", "second");
        h.send(new RuleEngine.Signal("chorus:reload_finished", old)); assertTrue(h.window("a").isPresent()); h.instant(); h.until(1_300_000); assertEquals(24, h.healed("player"));
    }
    @Test void evenAnEarlierCompletionWithoutAKillIsRecordedBeforeLaterWindowQualification() throws Exception {
        var h = new Harness(); h.instant(); var old = h.reloads.getFirst(); h.kill("a", "later");
        h.send(new RuleEngine.Signal("chorus:reload_finished", old)); h.until(100_000); assertTrue(h.window("a").isPresent()); assertEquals(0, h.healed("player"));
    }
    @Test void laterInsertionInTheSameReloadCanUseANewKillAfterTheSharedCureCooldown() throws Exception {
        var h = new Harness(true, EffectState.Mode.PVE); h.kill("a", "first"); h.reload(); var token = h.state().reloads().get("player").token();
        h.until(300_000); assertEquals(12, h.healed("player")); assertTrue(h.window("a").isEmpty()); h.until(1_300_000); h.kill("a", "second");
        assertEquals(token, h.state().reloads().get("player").token()); h.until(1_500_000); assertEquals(24, h.healed("player")); assertEquals(3, h.state().ammunition().get("a").magazine());
    }
    @Test void otherCureProducerSharesCooldownWhileAcceptedWindowIsConsumedOnce() throws Exception {
        var h = new Harness(); h.give("cure_bestow", "player", "ally"); h.kill("a", "first"); h.reload(); h.until(300_000);
        assertEquals(6, h.healed("ally")); assertEquals(12, h.healed("player")); assertTrue(h.window("a").isEmpty()); assertEquals(7_100_000, h.benefit().orElseThrow().deadline());
    }
    @Test void deniedAllyCureDoesNotInventBenevolenceAndUnknownHealStopsWithoutReplayingReload() throws Exception {
        var denied = new Harness(); denied.denied.add("ally"); denied.fire(); denied.reload(); denied.until(300_000);
        assertEquals(12, denied.healed("player")); assertEquals(0, denied.healed("ally")); assertTrue(denied.benefit().isEmpty());
        var unknown = new Harness(); unknown.fire(); unknown.reload(); unknown.until(200_000); unknown.unknownHeal = true;
        assertThrows(IllegalStateException.class, () -> unknown.until(300_000)); assertEquals(5, unknown.state().ammunition().get("a").magazine()); assertTrue(unknown.window("a").isEmpty());
        int calls = unknown.heals.size(); assertThrows(IllegalStateException.class, () -> unknown.until(400_000)); assertEquals(calls, unknown.heals.size());
    }
    @Test void stowKeepsTheWindowButDetachAndDeathRemoveItAndCheckpointPreservesPendingReload() throws Exception {
        var h = new Harness(); h.kill("a", "first"); h.equip("secondary", "normal", "enhanced"); assertTrue(h.window("a").isPresent());
        h.equip("primary", "normal", "enhanced"); h.reload(); h.restore(); h.until(300_000); assertEquals(12, h.healed("player"));
        h.kill("a", "second"); h.equip("primary", "none", "enhanced"); assertTrue(h.window("a").isEmpty());
        h.equip("primary", "normal", "enhanced"); h.kill("a", "third"); h.send(new RuleEngine.Signal("chorus:death", new EffectEvent("enemy", "player", new BuffInstance.Origin("enemy", "", "", ""), Set.of(), Map.of()))); assertTrue(h.window("a").isEmpty());
    }
    @Test void everyUnknownWindowAndReceiptLifetimeRequiresExplicitCalibration() throws Exception {
        var h = new Harness();
        for (String missing : calibration().keySet()) {
            var values = new HashMap<>(calibration()); values.remove(missing);
            var bad = new EffectSource("missing/" + missing, D + "heal_clip", "player", new BuffInstance.Origin("player", "perk", "a", ""), Set.of(), values);
            assertThrows(RuntimeException.class, () -> h.p.validateSourceChange((SourceChange) SourceChange.bind(bad).payload()));
        }
    }
}
