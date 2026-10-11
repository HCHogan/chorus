package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Real independent equipment containers drive current held selection; refill never impersonates a reload. */
public class DrawnWeaponGameTest {
    static WeaponReloadGameTest.Harness open(GameTestHelper h) throws Exception { return new WeaponReloadGameTest.Harness(h, d -> GamblersDodgeGameTest.merge(d, ThreadedSpikeGameTest.json("drawn_weapon"))); }
    static EffectSource bind(WeaponReloadGameTest.Harness t, ServerPlayer p, String triggerWeapon) { String holder = p.getUUID().toString(); var source = new EffectSource("fragment/" + holder, "test:drawn_weapon", holder, new BuffInstance.Origin(holder, "fragment", triggerWeapon, ""), Set.of()); t.runtime.bind(source); return source; }
    static void event(WeaponReloadGameTest.Harness t, String kind, EffectSource s) { t.runtime.start(new RuleEngine.Signal("test:" + kind, new EffectEvent(s.holder(), "unrelated_target", s.origin(), Set.of(), Map.of()))); t.settled(); }
    @GameCase(environment = "chorus_gametest:drawn_refill")
    public void actualReadiedContainerWeaponGetsRefillWithoutTouchingStowedWeaponsOrReloadPerks(GameTestHelper h) throws Exception {
        try (var t = open(h)) { var player = t.player("owner-"); var other = t.player("other-"); var s = bind(t, player, "owner-b"); t.killFact(player, "owner-a"); event(t, "refill_drawn", s);
            h.assertValueEqual(t.magazine("owner-a"), 5, "actual drawn gun refilled"); h.assertValueEqual(t.reserve("owner-a"), 8, "finite transfer from reserves"); h.assertValueEqual(t.magazine("owner-b"), 1, "stowed trigger weapon untouched"); h.assertValueEqual(t.magazine("other-a"), 1, "other holder untouched"); h.assertTrue(t.heals.isEmpty(), "refill did not fire reload_finished observer");
            h.assertTrue(t.state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("chorus_d2:kill_clip_window")), "kill window retained"); h.assertTrue(t.state().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("chorus_d2:kill_clip")), "no reload perk");
            t.draw(player, "secondary"); event(t, "refill_drawn", s); h.assertValueEqual(t.magazine("owner-b"), 5, "actual switch changes queried weapon");
            var equipment = PlayerEquipment.get(player); equipment.draw(player, Optional.empty(), equipment.revision()); int reserves = t.reserve("owner-b"); event(t, "refill_drawn", s); h.assertValueEqual(t.reserve("owner-b"), reserves, "empty hands is a safe empty selection");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:drawn_later", maxTicks = 15)
    public void deferredQueryUsesWeaponDrawnAtExecutionEvenAfterProviderRemoval(GameTestHelper h) throws Exception { delayed(h, false); }
    @GameCase(environment = "chorus_gametest:drawn_captured", maxTicks = 15)
    public void capturedSelectionRemainsTheOriginallyReadiedWeaponAfterSwitch(GameTestHelper h) throws Exception { delayed(h, true); }
    static void delayed(GameTestHelper h, boolean captured) throws Exception {
        var t = open(h);
        try { var player = t.player("owner-"); var s = bind(t, player, "owner-b"); event(t, captured ? "captured_drawn" : "later_drawn", s); t.runtime.unbind(s.instance()); t.draw(player, "secondary");
            h.runAfterDelay(4, () -> { try (t) { t.runtime.prepare(); t.settled(); h.assertValueEqual(t.magazine("owner-a"), captured ? 5 : 1, "original selected gun"); h.assertValueEqual(t.magazine("owner-b"), captured ? 1 : 5, "new selected gun"); h.assertTrue(t.heals.isEmpty(), "no reload reaction"); h.succeed(); } });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
