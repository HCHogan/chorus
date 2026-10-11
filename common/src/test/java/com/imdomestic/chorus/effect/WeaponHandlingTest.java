package com.imdomestic.chorus.effect;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class WeaponHandlingTest {
    static Loadout loadout(String drawn) { return new Loadout(Map.of("test:primary", WeaponReloadTest.gun("a"), "test:secondary", WeaponReloadTest.gun("b")), Optional.ofNullable(drawn).map(s -> "test:" + s)); }
    static final class Harness {
        final CompiledEffects program; EffectSession session; Loadout physical = Loadout.EMPTY;
        final List<WeaponHandling.Apply> applications = new ArrayList<>(); final List<String> cues = new ArrayList<>();
        final List<EffectState> observed = new ArrayList<>(); boolean allow = true, unknownApply, unknownCue; int sequence;
        Harness() throws Exception { this(load("weapon_handling")); }
        Harness(CompiledEffects program) {
            this.program = program; session = new EffectSession(program, EffectCheckpointTest.clock(), 256, EffectState.empty(), this::world);
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            return switch (request.command()) {
                case WeaponHandling.Apply a -> {
                    assertEquals(physical, a.change().before()); applications.add(a);
                    if (allow) physical = a.change().after();
                    if (unknownApply) throw new IllegalStateException("unknown physical selection result");
                    yield new WeaponHandling.Applied(a, allow);
                }
                case Action.CueCommand cue -> {
                    cues.add(cue.cue()); observed.add(state());
                    assertEquals(physical, state().equipment().get("player"), "Reaction must see committed physical selection");
                    if (unknownCue) throw new IllegalStateException("unknown reaction result");
                    yield RuleEngine.Empty.INSTANCE;
                }
                case WeaponReload.Verify query -> new WeaponReload.Verified(query, true);
                default -> throw new AssertionError(request.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        WeaponHandling.Plan plan() { return state().handling().get("player"); }
        void equip(Loadout loadout) { var before = state().equipment().getOrDefault("player", Loadout.EMPTY); physical = loadout; session.start(now(), new EquipmentChange("player", before, loadout).signal()); }
        WeaponHandling.Receipt select(String slot) {
            var request = new WeaponHandling.Request("player", "switch-" + ++sequence, Optional.ofNullable(slot).map(s -> "test:" + s));
            var resolved = program.switchWeapon(state(), request);
            session.observe(now(), resolved.emitted(), new WeaponHandling.Commit(state(), resolved.state()));
            return (WeaponHandling.Receipt) resolved.result();
        }
        void until(long now) { session.observe(now, List.of()); }
        void bind(String bundle, boolean enabled) {
            session.start(now(), enabled ? SourceChange.bind(new EffectSource(bundle, bundle, "player", new BuffInstance.Origin("player", bundle, "", ""), Set.of())) : SourceChange.remove(bundle));
        }
        void restore() {
            var saved = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint()));
            int before = applications.size(); session = EffectSession.restore(program, EffectCheckpointTest.clock(), 1, saved, this::world);
            assertEquals(before, applications.size()); assertEquals(saved, session.checkpoint());
        }
    }
    @Test void stowThenReadyCommitsAtExactIndependentDeadlinesAndBlocksFireReload() throws Exception {
        var h = new Harness(); h.equip(loadout("primary")); h.cues.clear();
        h.session.start(0, new WeaponReload.Request("player", "reload").signal());
        h.select("secondary"); assertTrue(h.state().reloads().isEmpty()); assertEquals(200_000, h.plan().dueAt());
        assertEquals(WeaponFire.Outcome.RESTRICTED, ((WeaponFire.Receipt) h.program.fire(h.state(), new WeaponFire.Request("player", "shot")).result()).outcome());
        assertEquals(WeaponReload.Outcome.RESTRICTED, ((WeaponReload.Receipt) h.program.reload(h.state(), new WeaponReload.Request("player", "reload2")).result()).outcome());
        assertEquals(1, h.state().ammunition().get("a").magazine());
        h.until(199_999); assertEquals(Optional.of("test:primary"), h.physical.drawn()); assertFalse(h.cues.contains("test:stowed"));
        h.until(200_000); assertTrue(h.physical.drawn().isEmpty()); assertEquals(WeaponHandling.Phase.READY, h.plan().phase());
        assertEquals("b", h.plan().origin().weapon()); assertEquals(500_000, h.plan().dueAt());
        assertEquals(1, Collections.frequency(h.cues, "test:stowed")); assertFalse(h.cues.contains("test:drawn"));
        assertEquals(WeaponFire.Outcome.EMPTY_HANDS, ((WeaponFire.Receipt) h.program.fire(h.state(), new WeaponFire.Request("player", "shot2")).result()).outcome());
        h.until(499_999); assertTrue(h.physical.drawn().isEmpty()); h.until(500_000);
        assertEquals(Optional.of("test:secondary"), h.physical.drawn()); assertNull(h.plan());
        assertEquals(1, Collections.frequency(h.cues, "test:drawn")); assertEquals(2, h.applications.size());
        assertEquals(WeaponFire.Outcome.ACCEPTED, ((WeaponFire.Receipt) h.program.fire(h.state(), new WeaponFire.Request("player", "shot3")).result()).outcome());
    }
    @Test void acceptedDurationsFreezeAndReadySamplesCurrentModifiersAfterStowReactions() throws Exception {
        var h = new Harness(); h.equip(loadout("primary")); h.bind("test:handling_fast", true); h.select("secondary");
        var first = h.plan(); assertEquals(100_000, first.dueAt()); h.bind("test:handling_fast", false);
        h.until(100_000); assertEquals(400_000, h.plan().dueAt()); assertEquals(.3, h.plan().duration().value());
        h.bind("test:handling_fast", true); h.until(399_999); assertNotNull(h.plan()); h.until(400_000); assertNull(h.plan());
        var reactions = new Harness(); reactions.equip(loadout("primary")); reactions.bind("test:stow_boost", true);
        reactions.select("secondary"); reactions.until(200_000); assertEquals(350_000, reactions.plan().dueAt());
        assertEquals(.15, reactions.plan().duration().value()); reactions.until(350_000); assertNull(reactions.plan());
        assertEquals(first, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(first), WeaponHandling.Plan.class));
    }
    @Test void sameTargetDoesNotExtendDeadlineAndNewTargetCannotReviveOldTimers() throws Exception {
        var h = new Harness(); h.equip(loadout("primary")); h.select("secondary"); var original = h.plan();
        h.until(50_000); assertEquals(WeaponHandling.Outcome.UNCHANGED, h.select("secondary").outcome()); assertEquals(original, h.plan());
        assertEquals(WeaponHandling.Outcome.CANCELLED, h.select("primary").outcome()); assertNull(h.plan());
        h.until(200_000); assertTrue(h.applications.isEmpty()); h.select("secondary");
        h.session.start(h.now(), new RuleEngine.Signal(WeaponHandling.DUE, original)); assertTrue(h.applications.isEmpty());
        h.until(400_000); assertTrue(h.physical.drawn().isEmpty()); var ready = h.plan(); h.until(450_000); h.select("primary");
        assertEquals(750_000, h.plan().dueAt()); assertEquals("a", h.plan().gear().instance());
        h.session.start(h.now(), new RuleEngine.Signal(WeaponHandling.DUE, ready)); assertEquals(1, h.applications.size());
        h.until(700_000); assertTrue(h.physical.drawn().isEmpty()); h.until(750_000); assertEquals(Optional.of("test:primary"), h.physical.drawn());
    }
    @Test void stowOnlyEmptyHandCancellationAndMixedUntimedWeaponsHaveFiniteImmediateBoundaries() throws Exception {
        var h = new Harness(); h.equip(loadout("primary")); h.select(null); h.until(200_000); assertNull(h.plan()); assertTrue(h.physical.drawn().isEmpty());
        h.select("secondary"); h.until(250_000); h.select(null); assertNull(h.plan()); h.until(600_000); assertTrue(h.physical.drawn().isEmpty());
        var instant = new Loadout.Gear("instant", "test:primary_ammo", Map.of());
        h.equip(new Loadout(Map.of("test:primary", WeaponReloadTest.gun("a"), "test:secondary", instant), Optional.of("test:primary")));
        h.select("secondary"); h.until(800_000); assertEquals(Optional.of("test:secondary"), h.physical.drawn()); assertNull(h.plan());
        h.select("primary"); assertTrue(h.physical.drawn().isEmpty()); assertEquals(1_100_000, h.plan().dueAt()); h.until(1_100_000); assertNull(h.plan());
        assertEquals(Optional.of("test:primary"), h.physical.drawn());
    }
    @Test void unrelatedEquipmentMutationRetainsPlanButRemovingOrMovingTargetCancels() throws Exception {
        var data = json("weapon_handling");
        data.getAsJsonObject("equipment").getAsJsonArray("slots").add(com.google.gson.JsonParser.parseString("{\"id\":\"test:armor_slot\",\"accepts\":[\"test:armor\"]}"));
        data.getAsJsonObject("equipment").getAsJsonArray("items").add(com.google.gson.JsonParser.parseString("{\"id\":\"test:armor\",\"tags\":[\"test:armor\"],\"effects\":{\"speed\":{\"bundle\":\"test:handling_fast\"}}}"));
        var h = new Harness(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow());
        h.equip(loadout("primary")); h.select("secondary"); var p = h.plan();
        var slots = new TreeMap<>(h.physical.slots()); slots.put("test:armor_slot", new Loadout.Gear("armor", "test:armor", Map.of()));
        h.equip(new Loadout(slots, h.physical.drawn())); assertEquals(p, h.plan());
        h.until(200_000); assertEquals(350_000, h.plan().dueAt(), "new armor changes the next phase only");
        h.equip(new Loadout(Map.of("test:primary", WeaponReloadTest.gun("a")), Optional.of("test:primary"))); assertNull(h.plan());
        h.equip(loadout("primary")); h.until(350_000); assertEquals(1, h.applications.size());
        h.select("secondary"); h.until(550_000); h.equip(Loadout.EMPTY); assertNull(h.plan()); h.until(900_000);
        assertTrue(h.physical.slots().isEmpty()); assertEquals(2, h.applications.size());
        h.equip(loadout("primary")); h.select("secondary");
        h.equip(new Loadout(Map.of("test:primary", WeaponReloadTest.gun("b"), "test:secondary", WeaponReloadTest.gun("a")), Optional.of("test:secondary")));
        assertNull(h.plan(), "moving the target must cancel even when the old drawn instance follows its slot");
        h.until(1_100_000); assertEquals(2, h.applications.size());
    }
    @Test void checkpointAtEitherPhaseRetainsTimeSourcePhysicalExpectationAndCounters() throws Exception {
        var h = new Harness(); h.equip(loadout("primary")); h.select("secondary"); h.until(99_999); h.restore();
        h.until(200_000); assertEquals(WeaponHandling.Phase.READY, h.plan().phase()); h.until(333_333); h.restore();
        h.until(500_000); assertNull(h.plan()); assertEquals(2, h.applications.size()); assertEquals(Optional.of("test:secondary"), h.physical.drawn());
    }
    @Test void hostRejectionClearsPlanAndUnknownPhysicalOutcomeIsNeverReplayed() throws Exception {
        var rejected = new Harness(); rejected.equip(loadout("primary")); rejected.select("secondary"); rejected.allow = false;
        rejected.until(200_000); assertNull(rejected.plan()); assertEquals(Optional.of("test:primary"), rejected.physical.drawn());
        rejected.until(900_000); assertEquals(1, rejected.applications.size());
        var unknown = new Harness(); unknown.equip(loadout("primary")); unknown.select("secondary"); unknown.unknownApply = true;
        assertThrows(IllegalStateException.class, () -> unknown.until(200_000)); assertTrue(unknown.physical.drawn().isEmpty());
        assertThrows(IllegalStateException.class, () -> unknown.until(300_000)); assertEquals(1, unknown.applications.size());
        assertThrows(IllegalStateException.class, unknown.session::checkpoint);
    }
    @Test void unknownPostCommitReactionKeepsTheCompletedSelectionAndCannotReplay() throws Exception {
        var h = new Harness(); h.equip(loadout(null)); h.select("primary"); h.unknownCue = true;
        assertThrows(IllegalStateException.class, () -> h.until(300_000));
        assertEquals(Optional.of("test:primary"), h.physical.drawn()); assertEquals(h.physical, h.state().equipment().get("player")); assertNull(h.plan());
        assertThrows(IllegalStateException.class, () -> h.until(600_000)); assertEquals(1, h.applications.size());
    }
    @Test void invalidRuntimeDurationLeavesOldSelectionAndAmmoUntouched() throws Exception {
        var data = json("weapon_handling");
        data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("handling").getAsJsonObject("stow").getAsJsonObject("value").addProperty("value", -1);
        var h = new Harness(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow()); h.equip(loadout("primary")); var before = h.state();
        assertThrows(IllegalArgumentException.class, () -> h.select("secondary")); assertEquals(before, h.state()); assertTrue(h.applications.isEmpty());
    }
    @Test void phaseQueriesCarryPrototypeTagsAndCannotBorrowAnotherHoldersTimingModifiers() throws Exception {
        var data = json("weapon_handling");
        for (var element : data.getAsJsonArray("bundles")) {
            var bundle = element.getAsJsonObject();
            if (bundle.get("id").getAsString().equals("test:handling_fast"))
                bundle.getAsJsonArray("modifiers").get(0).getAsJsonObject().add("if", com.google.gson.JsonParser.parseString("{\"type\":\"chorus:event_source_tag\",\"tag\":\"test:weapon\"}"));
        }
        var h = new Harness(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow()); h.equip(loadout("primary"));
        h.session.start(0, SourceChange.bind(new EffectSource("foreign", "test:handling_fast", "other", new BuffInstance.Origin("other", "foreign", "", ""), Set.of())));
        h.select("secondary"); assertEquals(200_000, h.plan().dueAt(), "foreign modifier borrowed");
        h.select("primary"); h.bind("test:handling_fast", true); h.select("secondary");
        assertEquals(100_000, h.plan().dueAt(), "prototype tag missing from phase query");
        assertTrue(h.plan().origin().tags().containsAll(Set.of("test:weapon", "chorus:weapon_stow")));
        h.until(100_000); assertEquals(250_000, h.plan().dueAt());
        assertTrue(h.plan().origin().tags().containsAll(Set.of("test:weapon", "chorus:weapon_ready")));
    }
}
