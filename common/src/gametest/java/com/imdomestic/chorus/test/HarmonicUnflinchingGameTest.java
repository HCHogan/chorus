package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.NoDistractionsGameTest.*;
import static com.imdomestic.chorus.test.UnflinchingGameTest.mods;
import static com.imdomestic.chorus.test.SelectedAbilityGameTest.choose;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.motion.AimImpulse;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

public class HarmonicUnflinchingGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        return NoDistractionsGameTest.open(h, false, data -> { UnflinchingGameTest.prepare(data); RadiantGameTest.merge(data, "harmonic_selection"); });
    }
    static void superElement(ProjectileGameTest.Harness t, String element) { choose(t, D + "super", element.isEmpty() ? "" : "test:super_" + element); }
    @GameCase(environment="chorus_gametest:harmonic_selection", maxTicks=40)
    public void realSuperSelectionChangesTheMixedArmorTierWithoutRestartingNoDistractions(GameTestHelper h) throws Exception {
        var t = open(h); mods(t, "harmonic", "harmonic", "solar"); superElement(t, "solar"); WeaponAimGameTest.input(t, true);
        at(t, 20, () -> { try (t) {
            t.runtime.prepare(); hit(t); angle(t, .90625 * .9 * .65 * .65);
            superElement(t, "arc"); h.assertTrue(active(t), "Super change restarted unchanged weapon perk"); hit(t); angle(t, .90625 * .9 * .75 * .65);
            superElement(t, ""); hit(t); angle(t, .90625 * .9 * .75 * .65); superElement(t, "solar"); hit(t); angle(t, .90625 * .9 * .65 * .65);
            near(h, t.owner.getHealth(), 6, "Super affinity does not change actual damage"); h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:harmonic_identity", maxTicks=20)
    public void anotherHolderOrMatchingGrenadeDoesNotGiveHarmonicItsElement(GameTestHelper h) throws Exception {
        var t = open(h); mods(t, "harmonic", "harmonic", "harmonic");
        String other = t.cow(12, 40, 12).getUUID().toString(); choose(t, other, D + "super", "test:super_solar"); choose(t, D + "grenade", "test:grenade_solar");
        WeaponAimGameTest.input(t, true);
        at(t, 4, () -> { try (t) {
            hit(t); angle(t, .90625 * .9); superElement(t, "void"); hit(t); angle(t, .90625 * .9);
            superElement(t, "solar"); hit(t); angle(t, .90625 * .9 * .65); h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:harmonic_switch", maxTicks=40)
    public void heldWeaponAndSuperAreMatchedIndependentlyThroughActualFireAndAim(GameTestHelper h) throws Exception {
        var t = open(h); mods(t, "harmonic", "harmonic", "solar"); superElement(t, "solar");
        put(t, "secondary", new Loadout.Gear("arc-weapon", "test:arc_rifle", Map.of("perk", "enhanced"), Map.of("stability", new Measure(50, Unit.STAT_POINT), "enhanced_delay", new Measure(.9, Unit.SECOND))));
        WeaponAimGameTest.input(t, true);
        at(t, 4, () -> {
            hit(t); angle(t, .90625 * .9 * .65); superElement(t, "arc"); hit(t); angle(t, .90625 * .9 * .75);
            PugilistGameTest.draw(t, "secondary"); WeaponAimGameTest.input(t, true);
        });
        at(t, 8, () -> { hit(t); angle(t, .90625 * .9 * .7); });
        at(t, 22, () -> { try (t) {
            t.runtime.prepare(); t.owner.setXRot(0); t.owner.setYRot(0); var target = t.cow(2, 40, 8);
            target.setPos(t.owner.getX(), t.owner.getEyeY() - target.getBbHeight() / 2, t.owner.getZ() + 5);
            h.assertValueEqual(t.runtime.fire(t.owner).outcome(), WeaponFire.Outcome.ACCEPTED, "actual Arc rifle fired"); near(h, target.getHealth(), 99, "actual target damage");
            h.assertValueEqual(PrecisionAmmoGameTest.magazine(t, "arc-weapon"), 11, "one round paid"); hit(t); angle(t, .90625 * .9 * .7 * .65); h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:harmonic_unknown", maxTicks=40)
    public void unknownHarmonicFlinchKeepsActualLossAndRotationWithoutRepeatingTheHit(GameTestHelper h) throws Exception {
        var t = open(h); mods(t, "harmonic", "harmonic", "harmonic"); superElement(t, "solar"); WeaponAimGameTest.input(t, true);
        at(t, 20, () -> { try (t) {
            t.runtime.prepare(); int[] writes = {0}; t.beforeWorld = q -> {
                if (q.command() instanceof AimImpulse.Command) { writes[0]++; t.world.apply(q); throw new IllegalStateException("Unknown Harmonic flinch"); }
            };
            boolean stopped = false; try { hit(t); } catch (IllegalStateException expected) { stopped = true; }
            h.assertTrue(stopped && t.runtime.failure().isPresent(), "unknown write did not stop runtime");
            near(h, t.owner.getHealth(), 9, "actual damage retained"); near(h, t.owner.getXRot(), -10 * .90625 * .9 * .65 * .65, "actual angle retained");
            var attacker = t.runtime.state().engine().domain().sources().get("attack").origin();
            boolean rejected = false; try { t.runtime.start(new RuleEngine.Signal("test:hit", new EffectEvent(attacker.owner(), t.owner.getUUID().toString(), attacker, Set.of(), Map.of()))); } catch (IllegalStateException expected) { rejected = true; }
            h.assertTrue(rejected && t.cues.isEmpty(), "replay or later cue"); h.assertValueEqual(writes[0], 1, "single angle write"); h.assertValueEqual(t.hits.size(), 1, "single damage write"); h.succeed();
        }});
    }
}
