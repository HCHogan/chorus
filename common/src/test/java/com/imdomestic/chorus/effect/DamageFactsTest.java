package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.combat.DamageFacts;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.rule.RuleEngine.Signal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DamageFactsTest {
    private static DamageCommand command(String owner) {
        return new DamageCommand("victim", new BuffInstance.Origin(owner, "shot", "weapon", ""),
                10000, "test:physical", Set.of("test:direct"), Set.of("chorus:weapon_kill"), false);
    }
    private static List<String> types(List<Signal> facts) { return facts.stream().map(Signal::type).toList(); }

    @Test void successfulDeathProtectionPublishesDamageButNeitherDeathNorKill() {
        var facts = DamageFacts.from(command("attacker"), new DamageReceipt("damage-1", DamageReceipt.Outcome.APPLIED,
                3, 4, 6, Optional.empty(), true));
        assertEquals(List.of("chorus:hit", "chorus:damage_taken", "chorus:death_prevented"), types(facts));
        var hit = (EffectEvent) facts.getFirst().payload();
        assertFalse(hit.flags().get("lethal")); assertTrue(hit.flags().get("death_prevented"));
        assertEquals(9, hit.numbers().get("effective_damage").value());
        assertEquals(13, hit.numbers().get("effective_with_absorption").value());
        assertEquals("damage-1", hit.references().get("damage_id"));
        assertFalse(hit.references().containsKey("death_id"));
        assertFalse(hit.tags().contains("chorus:weapon_kill"));
    }

    @Test void confirmedDeathAndKillShareIdentityButKillCreditIsExplicit() {
        var receipt = new DamageReceipt("damage-2", DamageReceipt.Outcome.APPLIED, 0, 0, 6, Optional.of("death-1"), false);
        var facts = DamageFacts.from(command("attacker"), receipt);
        assertEquals(List.of("chorus:hit", "chorus:damage_taken", "chorus:death", "chorus:kill"), types(facts));
        var death = (EffectEvent) facts.get(2).payload(); var kill = (EffectEvent) facts.getLast().payload();
        assertEquals(death.references(), kill.references()); assertEquals("death-1", death.references().get("death_id"));
        assertEquals("victim", death.victim()); assertEquals("attacker", kill.actor());
        assertTrue(death.flags().get("lethal")); assertFalse(death.tags().contains("chorus:weapon_kill"));
        assertTrue(kill.tags().contains("chorus:weapon_kill"));
        var uncredited = new DamageCommand("victim", command("attacker").source(), 10, "test:arc", Set.of(), Set.of(), false);
        assertFalse(((EffectEvent) DamageFacts.from(uncredited, receipt).getLast().payload()).tags().contains("chorus:weapon_kill"));
        assertEquals(List.of("chorus:hit", "chorus:damage_taken", "chorus:death"), types(DamageFacts.from(command(""), receipt)));
    }

    @Test void immuneAndBlockedHitsRemainVisibleWhileCancelledOrFailedAttemptsPublishNothing() {
        for (var outcome : List.of(DamageReceipt.Outcome.IMMUNE, DamageReceipt.Outcome.BLOCKED)) {
            var facts = DamageFacts.from(command("attacker"), new DamageReceipt("hit", outcome, 0, 0, 0, Optional.empty(), false));
            assertEquals(List.of("chorus:hit"), types(facts));
            var event = (EffectEvent) facts.getFirst().payload();
            assertFalse(event.flags().get("lethal")); assertFalse(event.flags().get("applied"));
            assertEquals(0, event.numbers().get("effective_damage").value());
            assertTrue(event.flags().get(outcome == DamageReceipt.Outcome.IMMUNE ? "immune" : "blocked"));
        }
        for (var outcome : List.of(DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.FAILED)) {
            assertTrue(DamageFacts.from(command("attacker"), new DamageReceipt("hit", outcome, 0, 0, 0, Optional.empty(), false)).isEmpty());
        }
    }
}
