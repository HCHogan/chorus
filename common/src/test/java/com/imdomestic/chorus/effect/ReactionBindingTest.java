package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReactionBindingTest {
    static EffectSource weapon(String instance, String owner, String weapon, boolean enhanced) {
        return new EffectSource(instance, "test:weapon", owner, new BuffInstance.Origin(owner, instance, weapon, ""), enhanced ? Set.of("chorus:enhanced") : Set.of());
    }
    static final EffectSource SOURCE = weapon("perk", "player", "weapon", true);
    static DamageCommand attack() { return new DamageCommand("enemy", SOURCE.origin(), 4, "minecraft:generic", Set.of("test:direct"), Set.of("chorus:weapon_kill"), false, Optional.of("test:damage")); }
    static final class Harness {
        final CompiledEffects program; final EffectSession session; final List<HealingCommand> heals = new ArrayList<>();
        final List<DamageCommand> damage = new ArrayList<>(); final List<ProjectileFlight.Launch> launches = new ArrayList<>();
        boolean lethal, unknownHeal; DamageReceipt.Outcome outcome = DamageReceipt.Outcome.APPLIED;
        Harness() throws Exception { this(load("reaction_binding")); }
        Harness(CompiledEffects program) {
            this.program = program;
            session = new EffectSession(engine(program), EffectState.empty().withSource(SOURCE), request -> switch (request.command()) {
                case HealingCommand command -> {
                    heals.add(command); if (unknownHeal) throw new IllegalStateException("Unknown origin healing receipt");
                    yield new HealingReceipt(request.id().toString(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
                }
                case DamageCommand command -> { damage.add(command); yield receipt(request.id().toString()); }
                case PositionQuery query -> new PositionQuery.Result(query, Optional.of(new WorldPosition("world", 0, 40, 0)));
                case DirectionQuery query -> new DirectionQuery.Result(query, Optional.of(new WorldDirection("world", 0, 1, 0)));
                case ProjectileFlight.Launch launch -> { launches.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("projectile")); }
                default -> throw new AssertionError(request.command());
            });
        }
        DamageReceipt receipt(String id) { return new DamageReceipt(id, outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? 4 : 0, lethal ? Optional.of("death/" + id) : Optional.empty(), false); }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        DamageSnapshot capture() { return program.captureDamage(state(), attack()); }
        void bind(EffectSource source) { session.start(now(), SourceChange.bind(source)); }
        void unbind() { session.start(now(), SourceChange.remove(SOURCE.instance())); }
        void fact(DamageCommand command) { session.observe(now(), DamageFacts.from(command, receipt("observed/" + heals.size()))); }
        void input(String type) { session.start(now(), new RuleEngine.Signal(type, new EffectEvent("player", "enemy", SOURCE.origin(), Set.of(), Map.of()))); }
        List<Double> amounts() { return heals.stream().map(HealingCommand::amount).toList(); }
        void impact() {
            session.start(now(), launches.getFirst().finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,
                    new WorldPosition("world", 0, 46, 0), Optional.of("enemy"), 0, 0, 0, now(), 1, 0, 1, 1, true)));
        }
    }
    @Test void capturedSourceSurvivesUnbindWhileCurrentRulesStopAndKillCreditRemainsExplicit() throws Exception {
        var h = new Harness(); var shot = h.capture(); h.unbind(); h.lethal = true; h.fact(shot.command("enemy"));
        assertEquals(List.of(2.0), h.amounts()); assertEquals("player", h.heals.getFirst().target());
        var reward = h.state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("test:kill_reward")).findFirst().orElseThrow();
        assertEquals("weapon", reward.origin().weapon()); assertTrue(reward.origin().tags().contains("chorus:enhanced"));
        var noCredit = new Harness(); var raw = attack();
        var noWeaponKill = new DamageCommand(raw.target(), raw.source(), raw.amount(), raw.damageType(), raw.tags(), Set.of(), false, raw.scalingProfile());
        var nonWeapon = noCredit.program.captureDamage(noCredit.state(), noWeaponKill); noCredit.unbind(); noCredit.lethal = true; noCredit.fact(nonWeapon.command("enemy"));
        assertEquals(List.of(2.0), noCredit.amounts()); assertTrue(noCredit.state().buffs().instances().isEmpty());
    }
    @Test void currentSourceAndCapturedSourceNeverExecuteTheSameOriginRuleTwice() throws Exception {
        var h = new Harness(); h.fact(h.capture().command("enemy")); assertEquals(List.of(10.0, 2.0), h.amounts());
        assertEquals(1, h.heals.stream().filter(c -> c.tags().contains("test:origin_heal")).count());
    }
    @Test void replacementRetainsCapturedEnhancementButCurrentRulesReadNewSources() throws Exception {
        var h = new Harness(); var shot = h.capture(); h.bind(weapon("perk", "player", "weapon", false));
        h.bind(weapon("new-perk", "player", "weapon", true)); h.fact(shot.command("enemy"));
        assertEquals(List.of(10.0, 10.0, 2.0), h.amounts());
        assertTrue(h.heals.getLast().source().tags().contains("chorus:enhanced"));
        assertEquals("perk", h.heals.getLast().source().source());
    }
    @Test void emptyReleaseSelectionCannotAcquireAnOriginRuleAddedDuringFlight() throws Exception {
        var h = new Harness(); h.unbind(); var shot = h.capture(); h.bind(SOURCE);
        var command = h.program.prepareDamage(h.state(), shot.command("enemy"));
        assertTrue(command.reactions().orElseThrow().sources().isEmpty()); h.fact(command); assertEquals(List.of(10.0), h.amounts());
    }
    @Test void anotherOwnerAndAnotherWeaponCannotBorrowTheCapturedAttack() throws Exception {
        var h = new Harness(); h.bind(weapon("other-owner", "other", "weapon", true)); h.bind(weapon("other-weapon", "player", "weapon-2", true));
        var shot = h.capture(); assertEquals(2, shot.attack().reactions().orElseThrow().sources().size()); h.unbind(); h.fact(shot.command("enemy"));
        assertEquals(List.of(2.0), h.amounts());
        assertThrows(IllegalArgumentException.class, () -> new ReactionSnapshot("other", h.program.program(), List.of(SOURCE)));
        assertThrows(IllegalArgumentException.class, () -> new ReactionSnapshot("player", h.program.program(), List.of(SOURCE, SOURCE)));
    }
    @Test void managedImmediateDelayedAndPhysicalProjectilePathsCarryTheSameSelection() throws Exception {
        var immediate = new Harness(); immediate.input("test:instant"); assertEquals(List.of(10.0, 2.0), immediate.amounts());
        var delayed = new Harness(); delayed.input("test:delay"); delayed.unbind(); delayed.session.observe(100_000, List.of());
        assertEquals(List.of(2.0), delayed.amounts()); assertTrue(delayed.damage.getFirst().reactions().isPresent());
        var projectile = new Harness(); projectile.input("test:launch"); projectile.unbind(); projectile.impact();
        assertEquals(List.of(2.0), projectile.amounts()); assertTrue(projectile.damage.getFirst().snapshot().isPresent());
    }
    @Test void incompatibleCatalogueIsRejectedBeforeDamageAndVersionLabelsAreNotEnough() throws Exception {
        var original = new Harness(); var shot = original.capture(); var changed = json("reaction_binding");
        changed.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(4).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("amount").addProperty("value", 20);
        var next = new Harness(compile(changed));
        assertThrows(IllegalArgumentException.class, () -> next.program.prepareDamage(next.state(), shot.command("enemy")));
        assertThrows(IllegalArgumentException.class, () -> next.program.outgoing(next.state(), shot.command("enemy"), 4));
        assertEquals(shot.attack().reactions(), load("reaction_binding").prepareReactions(original.state(), shot.command("enemy")).reactions());
        var replacement = new ReactionSnapshot("player", original.program.program(), List.of());
        assertThrows(IllegalArgumentException.class, () -> shot.command("enemy").withReactions(replacement));
    }
    @Test void cancelledOrUnknownWorldResultsDoNotInventOrReplayReactions() throws Exception {
        var cancelled = new Harness(); cancelled.outcome = DamageReceipt.Outcome.CANCELLED; cancelled.input("test:instant"); assertTrue(cancelled.heals.isEmpty());
        var unknown = new Harness(); var shot = unknown.capture(); unknown.unbind(); unknown.unknownHeal = true;
        assertThrows(IllegalStateException.class, () -> unknown.fact(shot.command("enemy")));
        assertEquals(List.of(2.0), unknown.amounts()); assertTrue(unknown.session.state().engine().pending().isPresent());
    }
    @Test void originConditionsReadCurrentBuffStateAndOnlySourceIdentityIsCaptured() throws Exception {
        var data = json("reaction_binding"); var rule = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(3).getAsJsonObject();
        rule.getAsJsonObject("if").getAsJsonArray("of").add(JsonParser.parseString("{\"type\":\"chorus:has_buff\",\"buff\":\"test:kill_reward\"}"));
        var h = new Harness(compile(data)); var shot = h.capture(); h.unbind(); h.fact(shot.command("enemy")); assertTrue(h.heals.isEmpty());
        h.lethal = true; h.fact(shot.command("enemy")); assertTrue(h.heals.isEmpty());
        h.lethal = false; h.fact(shot.command("enemy")); assertEquals(List.of(2.0), h.amounts());
    }
    @Test void codecDefaultsAndInvalidBindingScopesAreCheckedAtLoadTime() throws Exception {
        var program = load("reaction_binding").program();
        assertEquals(program, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow());
        assertEquals(EffectProgram.ReactionBinding.CURRENT_OWNER_BUNDLE, program.bundles().getFirst().rules().getFirst().binding());
        for (boolean buff : List.of(false, true)) {
            var data = json("reaction_binding"); var bundle = data.getAsJsonArray("bundles").get(0).getAsJsonObject();
            if (buff) bundle.addProperty("scope", "buff"); else bundle.getAsJsonArray("rules").get(3).getAsJsonObject().addProperty("on", "chorus:reload_finished");
            assertTrue(assertThrows(IllegalStateException.class, () -> compile(data)).getMessage().contains("origin_bundle requires"));
        }
    }
}
