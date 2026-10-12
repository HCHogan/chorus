package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

public class SelectedAbilityGameTest {
    static void choose(ProjectileGameTest.Harness t, String holder, String slot, String ability) {
        var before = t.runtime.state().engine().domain().abilities().getOrDefault(holder, AbilityLoadout.EMPTY);
        var slots = new HashMap<>(before.slots()); if (ability.isEmpty()) slots.remove(slot); else slots.put(slot, ability);
        t.runtime.abilities(new AbilityChange(holder, before, new AbilityLoadout(slots)));
    }
    static void choose(ProjectileGameTest.Harness t, String slot, String ability) { choose(t, t.owner.getUUID().toString(), slot, ability); }
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "selected_ability", _ -> {}, true); String owner = t.owner.getUUID().toString();
        t.runtime.bind(new EffectSource("selected", "test:selected", owner, new BuffInstance.Origin(owner, "selected", "", ""), Set.of())); return t;
    }
    static void probe(ProjectileGameTest.Harness t, String kind) {
        String owner = t.owner.getUUID().toString(); t.runtime.start(new RuleEngine.Signal("test:" + kind, new EffectEvent(owner, owner, new BuffInstance.Origin(owner, "old-attack", "", "test:arc"), Set.of("test:solar"), Map.of())));
    }
    @GameCase(environment="chorus_gametest:selected_ability_identity")
    public void actualHealingReadsTheCorrectHolderAndSlotWithoutPassiveTagMarkers(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            String other = t.cow(10, 40, 10).getUUID().toString(); choose(t, other, "test:super", "test:solar"); choose(t, "test:grenade", "test:solar_grenade");
            String owner = t.owner.getUUID().toString(); t.runtime.bind(new EffectSource("spoof", "test:spoof", owner, new BuffInstance.Origin(owner, "spoof", "", "test:solar"), Set.of("test:solar")));
            probe(t, "probe"); near(h, t.owner.getHealth(), 10, "foreign selection, other slot and tags did not qualify");
            choose(t, "test:super", "test:solar"); probe(t, "probe"); near(h, t.owner.getHealth(), 11, "committed selection healed");
            choose(t, "test:super", "test:arc"); probe(t, "probe"); choose(t, "test:super", ""); probe(t, "probe"); near(h, t.owner.getHealth(), 11, "changed or cleared selection did not qualify");
            h.assertTrue(t.runtime.failure().isEmpty(), "selection runtime failed"); h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:selected_ability_cast")
    public void aReplacementCastKeepsBaseSelectionConditionsAndResolvedHealingCredit(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            choose(t, "test:super", "test:solar"); String owner = t.owner.getUUID().toString();
            t.runtime.bind(new EffectSource("override", "test:override", owner, new BuffInstance.Origin(owner, "override", "", ""), Set.of()));
            var heals = new ArrayList<HealingCommand>(); t.beforeWorld = q -> { if (q.command() instanceof HealingCommand c) heals.add(c); };
            var receipt = t.runtime.useAbility(t.owner, "test:super"); h.assertValueEqual(receipt.outcome(), AbilityUse.Outcome.ACCEPTED, "replacement cast accepted");
            near(h, t.owner.getHealth(), 12, "base-selected condition inside resolved cast"); h.assertValueEqual(heals.getLast().source().ability(), "test:arc", "resolved credit");
            h.assertValueEqual(t.runtime.state().engine().domain().abilities().get(owner).slots().get("test:super"), "test:solar", "base selection preserved"); h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:selected_ability_delay", maxTicks=15)
    public void detachedRulesReadLaterSelectionsOnRealServerTicksAfterTheirSourceIsRemoved(GameTestHelper h) throws Exception {
        var t = open(h); choose(t, "test:super", "test:arc"); probe(t, "delay");
        at(t, 1, () -> choose(t, "test:super", "test:solar"));
        at(t, 2, () -> {
            t.runtime.prepare(); near(h, t.owner.getHealth(), 14, "delayed condition read new selection"); probe(t, "delay");
            t.runtime.unbind("selected"); choose(t, "test:super", "test:arc");
        });
        at(t, 4, () -> { try (t) { t.runtime.prepare(); near(h, t.owner.getHealth(), 14, "detached condition did not retain obsolete selection"); h.assertTrue(t.runtime.failure().isEmpty(), "delayed runtime failed"); h.succeed(); }});
    }
}
