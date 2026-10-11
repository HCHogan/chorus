package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.network.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

public class WeaponHandlingGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "weapon_handling", _ -> {}, true);
        for (String weapon : List.of("a", "b")) {
            var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(weapon, "test:rifle", Map.of("perk", "kill_clip")));
            t.owner.getInventory().setItem(0, stack); var equipment = PlayerEquipment.get(t.owner);
            equipment.swap(t.owner, weapon.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
        }
        return t;
    }
    static String holder(ProjectileGameTest.Harness t) { return t.owner.getUUID().toString(); }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static WeaponHandling.Plan plan(ProjectileGameTest.Harness t) { return state(t).handling().get(holder(t)); }
    static Optional<String> drawn(ProjectileGameTest.Harness t) { return PlayerEquipment.get(t.owner).snapshot().drawn(); }
    static void draw(ProjectileGameTest.Harness t, String slot) {
        var equipment = PlayerEquipment.get(t.owner); equipment.draw(t.owner, Optional.ofNullable(slot).map(s -> "test:" + s), equipment.revision());
    }
    static void bind(ProjectileGameTest.Harness t, String bundle, boolean enabled) {
        t.runtime.start(enabled ? SourceChange.bind(new EffectSource(bundle, bundle, holder(t), new BuffInstance.Origin(holder(t), bundle, "", ""), Set.of())) : SourceChange.remove(bundle));
    }
    static void settled(ProjectileGameTest.Harness t) { t.runtime.prepare(); t.h.assertTrue(t.runtime.failure().isEmpty() && t.runtime.state().idle(), "handling runtime failed: " + t.runtime.failure()); }
    static void agrees(ProjectileGameTest.Harness t) { t.h.assertValueEqual(state(t).equipment().get(holder(t)).drawn(), drawn(t), "physical and rule selection disagree"); }

    @GameCase(environment="chorus_gametest:weapon_handling_timing", maxTicks=35)
    public void actualSelectionCommitsAfterBothPhasesAndFireReloadRespectTheWait(GameTestHelper h) throws Exception {
        var t = open(h); try {
            draw(t, "primary"); h.assertTrue(drawn(t).isEmpty(), "ready selected immediately");
            h.assertTrue(t.runtime.fire(t.owner).outcome() == WeaponFire.Outcome.EMPTY_HANDS, "fired before ready");
            RampageGameTest.at(t, 5, () -> { settled(t); h.assertTrue(drawn(t).isEmpty(), "early ready"); });
            RampageGameTest.at(t, 6, () -> {
                settled(t); h.assertValueEqual(drawn(t), Optional.of("test:primary"), "ready completion"); agrees(t);
                h.assertTrue(t.runtime.reload(t.owner).outcome() == WeaponReload.Outcome.ACCEPTED, "reload initial weapon");
                draw(t, "secondary"); h.assertTrue(state(t).reloads().isEmpty(), "switch did not interrupt reload");
                h.assertTrue(t.runtime.fire(t.owner).outcome() == WeaponFire.Outcome.RESTRICTED, "fire during stow");
                h.assertTrue(t.runtime.reload(t.owner).outcome() == WeaponReload.Outcome.RESTRICTED, "reload during stow");
                h.assertValueEqual(state(t).ammunition().get("a").magazine(), 1, "stow charged a shot");
                RampageGameTest.at(t, 3, () -> { settled(t); h.assertValueEqual(drawn(t), Optional.of("test:primary"), "early stow"); });
                RampageGameTest.at(t, 4, () -> { settled(t); agrees(t); h.assertTrue(drawn(t).isEmpty() && plan(t).phase() == WeaponHandling.Phase.READY, "stow did not enter ready"); });
                RampageGameTest.at(t, 9, () -> { settled(t); h.assertTrue(drawn(t).isEmpty(), "early second ready"); });
                RampageGameTest.at(t, 10, () -> { try (t) {
                    settled(t); agrees(t); h.assertValueEqual(drawn(t), Optional.of("test:secondary"), "new ready completion"); h.assertTrue(plan(t) == null, "finished plan retained");
                    h.assertTrue(t.runtime.fire(t.owner).outcome() == WeaponFire.Outcome.ACCEPTED, "ready did not allow fire");
                    h.assertValueEqual(t.projectiles.size(), 1, "real weapon shot"); h.assertValueEqual(state(t).ammunition().get("b").magazine(), 0, "new weapon paid");
                    h.assertValueEqual(state(t).ammunition().get("a").magazine(), 1, "old weapon changed"); h.succeed();
                } });
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:weapon_handling_resample", maxTicks=30)
    public void physicalStowKeepsAcceptedTimeAndReadySeesStowTriggeredModifiers(GameTestHelper h) throws Exception {
        var t = open(h); try {
            draw(t, "primary"); RampageGameTest.at(t, 6, () -> {
                settled(t); bind(t, "test:handling_fast", true); draw(t, "secondary"); var accepted = plan(t);
                h.assertValueEqual(accepted.dueAt() - accepted.startedAt(), 100_000L, "stow multiplier");
                bind(t, "test:handling_fast", false); bind(t, "test:stow_boost", true);
                RampageGameTest.at(t, 2, () -> {
                    settled(t); h.assertTrue(drawn(t).isEmpty(), "frozen stow did not complete");
                    h.assertValueEqual(plan(t).dueAt() - plan(t).startedAt(), 150_000L, "ready ignored committed stow buff");
                    RampageGameTest.at(t, 3, () -> { try (t) { settled(t); agrees(t); h.assertValueEqual(drawn(t), Optional.of("test:secondary"), "boosted ready"); h.succeed(); } });
                });
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:weapon_handling_remove", maxTicks=30)
    public void removingAndReequippingTheTargetCannotReviveItsOldSwitch(GameTestHelper h) throws Exception {
        var t = open(h); try {
            draw(t, "primary"); RampageGameTest.at(t, 6, () -> {
                settled(t); draw(t, "secondary"); var old = plan(t);
                var equipment = PlayerEquipment.get(t.owner); equipment.swap(t.owner, "test:secondary", 0, equipment.revision());
                h.assertTrue(plan(t) == null, "target removal retained switch");
                equipment.swap(t.owner, "test:secondary", 0, equipment.revision());
                t.runtime.start(new RuleEngine.Signal(WeaponHandling.DUE, old));
                RampageGameTest.at(t, 12, () -> { try (t) {
                    settled(t); agrees(t); h.assertTrue(plan(t) == null, "old switch revived"); h.assertValueEqual(drawn(t), Optional.of("test:primary"), "old timer stole selection"); h.succeed();
                } });
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    static EquipmentPayloads.Request request(EquipmentPayloads.View view, EquipmentPayloads.Operation operation, String slot) {
        return new EquipmentPayloads.Request(view.screen(), view.session(), view.sequence(), operation, slot, "", 0);
    }
    @GameCase(environment="chorus_gametest:weapon_handling_network", maxTicks=25)
    public void authenticatedSelectionConsumesRevisionAndOldViewCannotCancelNewIntent(GameTestHelper h) throws Exception {
        var t = open(h); try {
            var views = new ArrayList<EquipmentPayloads.View>(); var network = new EquipmentNetworkServer((_, view) -> views.add(view));
            network.visit(t.owner, new EquipmentPayloads.Visit(UUID.randomUUID(), true)); long oldRevision = PlayerEquipment.get(t.owner).revision();
            var first = request(views.getLast(), EquipmentPayloads.Operation.DRAW, "test:primary"); network.request(t.owner, first); var pending = plan(t);
            h.assertTrue(pending != null && drawn(t).isEmpty(), "network draw bypassed ready");
            h.assertTrue(PlayerEquipment.get(t.owner).revision() > oldRevision, "accepted intent did not consume revision");
            boolean stale = false; try { PlayerEquipment.get(t.owner).draw(t.owner, Optional.of("test:secondary"), oldRevision); } catch (IllegalStateException expected) { stale = true; }
            h.assertTrue(stale, "stale container revision accepted");
            network.request(t.owner, first); h.assertValueEqual(views.getLast().reply(), EquipmentPayloads.Reply.STALE, "replayed view"); h.assertValueEqual(plan(t), pending, "replayed view changed deadline");
            network.request(t.owner, request(views.getLast(), EquipmentPayloads.Operation.DRAW, "test:secondary")); h.assertValueEqual(plan(t).gear().instance(), "b", "new target");
            network.request(t.owner, request(views.getLast(), EquipmentPayloads.Operation.STOW, "")); h.assertTrue(plan(t) == null, "empty hand cancel retained ready");
            RampageGameTest.at(t, 10, () -> { try (t) { settled(t); agrees(t); h.assertTrue(drawn(t).isEmpty(), "cancelled request later drew a weapon"); h.succeed(); } });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:weapon_handling_death", maxTicks=25)
    public void deadOwnerLosesPendingSelectionBeforeItsOldCompletion(GameTestHelper h) throws Exception {
        var t = open(h); try {
            draw(t, "primary"); t.owner.setHealth(0); settled(t);
            h.assertTrue(plan(t) == null, "dead owner retained pending ready");
            RampageGameTest.at(t, 10, () -> { try (t) { settled(t); h.assertTrue(drawn(t).isEmpty(), "dead owner was armed by old timer"); h.succeed(); } });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:weapon_handling_spectator", maxTicks=25)
    public void hostRejectsAReadyCompletionAfterTheOwnerBecomesSpectator(GameTestHelper h) throws Exception {
        var t = open(h); try {
            draw(t, "primary"); t.owner.setGameMode(GameType.SPECTATOR);
            RampageGameTest.at(t, 10, () -> { try (t) {
                settled(t); agrees(t); h.assertTrue(plan(t) == null && drawn(t).isEmpty(), "ineligible physical completion accepted");
                h.assertTrue(t.cues.stream().anyMatch(c -> c.cue().equals("test:switch_cancelled")), "missing cancellation fact"); h.succeed();
            } });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:weapon_handling_unknown", maxTicks=25)
    public void unknownDrawnReactionRetainsPhysicalAndDomainSelectionWithoutReplay(GameTestHelper h) throws Exception {
        var t = open(h); try {
            draw(t, "primary"); t.failAfterCue = true;
            h.runAfterDelay(8, () -> { try (t) {
                h.assertTrue(t.runtime.failure().isPresent(), "injected reaction did not fail"); agrees(t);
                h.assertValueEqual(drawn(t), Optional.of("test:primary"), "committed draw rolled back"); h.assertTrue(plan(t) == null, "committed ready remained active");
                int count = t.cues.size(); long revision = PlayerEquipment.get(t.owner).revision(); t.runtime.prepare();
                h.assertValueEqual(t.cues.size(), count, "unknown reaction replayed"); h.assertValueEqual(PlayerEquipment.get(t.owner).revision(), revision, "draw replayed"); h.succeed();
            } });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    static void restorePhase(ProjectileGameTest.Harness t, int ticks, String expected) {
        var h = t.h; var saved = t.runtime.checkpoint();
        var stored = new MinecraftEffectRuntime.Checkpoint(saved.dimension(), saved.gameTime(),
                com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec.decode(com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec.encode(saved.effects())), saved.equipmentOwners());
        var accepted = plan(t); int cues = t.cues.size(); long revision = PlayerEquipment.get(t.owner).revision();
        var program = t.runtime.program(); t.runtime.close();
        var resumed = MinecraftEffectRuntime.restore(h.getLevel(), program, stored,
                new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), t.world, MinecraftEffectRuntime::nativeSource);
        try {
            h.assertValueEqual(resumed.state().engine().domain().handling().get(holder(t)), accepted, "restored phase changed");
            h.assertValueEqual(t.cues.size(), cues, "restore replayed phase facts"); h.assertValueEqual(PlayerEquipment.get(t.owner).revision(), revision, "restore repeated physical commit");
            h.runAfterDelay(ticks, () -> { try (resumed; t) {
                resumed.prepare(); h.assertTrue(resumed.failure().isEmpty(), "restored handling failed");
                h.assertTrue(resumed.state().engine().domain().handling().isEmpty(), "restored phase retained after deadline");
                h.assertValueEqual(drawn(t), Optional.of("test:" + expected), "restored deadline did not commit physical selection");
                h.assertValueEqual(resumed.state().engine().domain().equipment().get(holder(t)).drawn(), drawn(t), "restored projection disagrees"); h.succeed();
            } });
        } catch (Exception | Error error) { resumed.close(); t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:weapon_handling_restore_ready", maxTicks=25)
    public void readyCheckpointResumesAgainstTheSamePhysicalContainerAtItsOriginalDeadline(GameTestHelper h) throws Exception {
        var t = open(h); try { draw(t, "primary"); RampageGameTest.at(t, 5, () -> restorePhase(t, 1, "primary")); }
        catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:weapon_handling_restore_stow", maxTicks=30)
    public void stowCheckpointResumesThenAcceptsTheNewWeaponsReadyPhase(GameTestHelper h) throws Exception {
        var t = open(h); try {
            draw(t, "primary"); RampageGameTest.at(t, 6, () -> {
                draw(t, "secondary"); RampageGameTest.at(t, 3, () -> restorePhase(t, 7, "secondary"));
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
}
