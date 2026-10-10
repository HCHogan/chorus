package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecoveryTest {
    private record Heal(long time, HealingCommand command) {}
    private static final class Harness {
        final List<Heal> heals = new ArrayList<>();
        final List<String> order = new ArrayList<>();
        final EffectSource source = source("test:recovery_inputs");
        final EffectSession session;
        Harness(CompiledEffects program, EffectState.Mode mode) {
            session = new EffectSession(engine(program), EffectState.empty().withSource(source).withMode(mode), request -> {
                if (request.command() instanceof Action.CueCommand cue) { order.add(cue.cue()); return RuleEngine.Empty.INSTANCE; }
                var command = (HealingCommand) request.command(); heals.add(new Heal(time(), command)); order.add("heal");
                return receipt(command);
            });
        }
        HealingReceipt receipt(HealingCommand command) { return new HealingReceipt("heal/" + heals.size(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0); }
        void send(long at, String type, int tier, double duration) { session.start(at, input(source, type, tier, duration)); }
        double sum() { return heals.stream().mapToDouble(value -> value.command().amount()).sum(); }
        long time() { return session.state().engine().timeMicros(); }
        EffectState state() { return session.state().engine().domain(); }
    }
    private static RuleEngine.Signal input(EffectSource source, String type, int tier, double duration) {
        return new RuleEngine.Signal(type, new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(),
                Map.of("tier", new Measure(tier, Unit.COUNT), "duration", new Measure(duration, Unit.SECOND))));
    }
    private static JsonObject declaration(JsonObject data) { return data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("health_recovery").get(0).getAsJsonObject(); }
    @Test void finalFractionalIntervalHealsBeforeExpiryReactionAndDoesNotRepeat() throws Exception {
        var data = json("restoration");
        data.getAsJsonArray("bundles").get(1).getAsJsonObject().add("rules", JsonParser.parseString("""
            [{"id":"end","on":"chorus:buff_ended","if":{"type":"chorus:own_buff"},"do":[{"type":"chorus:play_cue","cue":"test:ended"}]}]
            """));
        var test = new Harness(compile(data), EffectState.Mode.PVE);
        test.send(0, "test:restoration", 1, .070001); test.send(200_000, "test:noop", 1, 1);
        assertEquals(List.of(50_000L, 70_001L), test.heals.stream().map(Heal::time).toList());
        assertEquals(.070001 * 3.5, test.sum(), 1e-12); assertEquals(List.of("heal", "heal", "test:ended"), test.order);
        assertTrue(test.state().buffs().instances().isEmpty());
        test.send(500_000, "test:noop", 1, 1); assertEquals(2, test.heals.size());
    }
    @Test void strengthChangesSplitTheIntervalAndReapplyPreservesHistoryAndHighestTier() throws Exception {
        var test = new Harness(load("restoration"), EffectState.Mode.PVE);
        test.send(0, "test:restoration", 1, .12); test.send(30_000, "test:restoration", 2, .08);
        var active = test.state().buffs().instances().values().iterator().next(); assertEquals(150_000, active.deadline()); assertEquals(120_000, active.longestDurationMicros());
        test.send(60_000, "test:restoration", 1, .02); active = test.state().buffs().instances().values().iterator().next();
        assertEquals(2, active.tier()); assertEquals(180_000, active.deadline());
        test.send(180_000, "test:noop", 1, 1); assertEquals(.03 * 3.5 + .15 * 5, test.sum(), 1e-12);
    }
    @Test void suppressedSourceKeepsItsLifetimeAndResumesWhenWinnerExpires() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var test = new Harness(load("restoration"), mode);
            test.send(0, "test:restoration", 1, .2); test.send(0, "test:rift", 1, .070001); test.send(200_000, "test:noop", 1, 1);
            double rift = mode == EffectState.Mode.PVE ? 4 : 3.5; double restoration = mode == EffectState.Mode.PVE ? 3.5 : 1.75;
            assertEquals(.070001 * rift + .129999 * restoration, test.sum(), 1e-12);
            assertTrue(test.heals.get(1).command().tags().contains("chorus_d2:healing_rift"));
            assertTrue(test.heals.get(2).command().tags().contains("chorus_d2:restoration"));
        }
    }
    @Test void pauseAndRemovalSettleOnlyElapsedActiveTime() throws Exception {
        var data = json("restoration"); var definition = data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition");
        definition.addProperty("instanced_by", "weapon"); definition.addProperty("on_stow", "pause");
        var test = new Harness(compile(data), EffectState.Mode.PVE);
        test.send(0, "test:restoration", 1, .070001); test.send(25_000, "chorus:weapon_stowed", 1, 1);
        test.send(75_000, "chorus:weapon_drawn", 1, 1); assertEquals(1, test.heals.size());
        test.send(200_000, "test:noop", 1, 1); assertEquals(.070001 * 3.5, test.sum(), 1e-12);
        assertEquals(List.of(25_000L, 100_000L, 120_001L), test.heals.stream().map(Heal::time).toList());
        var removed = new Harness(load("restoration"), EffectState.Mode.PVE);
        removed.send(0, "test:restoration", 1, 1); removed.send(25_000, "test:remove", 1, 1); removed.send(200_000, "test:noop", 1, 1);
        assertEquals(.025 * 3.5, removed.sum(), 1e-12);
    }
    @Test void channelSelectionKeepsPrioritySourceAndIndependentTargetsAndChannels() {
        var owner = new BuffInstance.Origin("owner", "source", "weapon", "");
        var low = new Recovery.Offer("a", "one", "test:shared", 0, 50, owner, Set.of("test:low"));
        var high = new Recovery.Offer("b", "one", "test:shared", 1, 20, owner, Set.of("test:high"));
        var other = new Recovery.Offer("c", "two", "test:shared", 0, 30, owner, Set.of());
        var independent = new Recovery.Offer("d", "one", "test:independent", 0, 10, owner, Set.of());
        var batch = Recovery.integrate(List.of(low, independent, other, high), 0, 10_001, List.of());
        assertEquals(List.of(low), batch.suppressed()); assertEquals(3, batch.allocations().size());
        assertEquals(60 * .010001, batch.allocations().stream().mapToDouble(value -> value.command().amount()).sum(), 1e-12);
        assertTrue(batch.allocations().stream().allMatch(value -> value.command().source().equals(owner)));
        var tie = new Recovery.Offer("0", "one", "test:shared", 1, 20, owner, Set.of());
        assertEquals(tie, Recovery.integrate(List.of(high, tie), 0, 1, List.of()).allocations().getFirst().offer());
        assertThrows(IllegalArgumentException.class, () -> Recovery.integrate(List.of(low, low), 0, 1, List.of()));
    }
    @Test void receiptReplayCannotDuplicateFinalIntervalAndWrongReceiptStopsTheBoundary() throws Exception {
        var source = source("test:recovery_inputs"); var engine = engine(load("restoration"));
        var initial = engine.initial(EffectState.empty().withSource(source));
        var applied = pump(engine, engine.transition(initial, new RuleEngine.Start(0, input(source, "test:restoration", 1, .020001))));
        var waiting = send(engine, applied.state(), 100_000, "test:noop", event(source)); var request = waiting.actions().getFirst();
        var command = (HealingCommand) request.command(); assertEquals(.020001 * 3.5, command.amount(), 1e-12);
        var receipt = new HealingReceipt("final", command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
        var done = complete(engine, waiting, receipt); assertTrue(done.state().idle());
        assertEquals(done.state(), engine.transition(done.state(), new RuleEngine.Completed(request.id(), receipt)).state());
        var badCommand = new HealingCommand(command.target(), command.source(), command.amount() + 1, command.tags());
        var bad = engine.transition(waiting.state(), new RuleEngine.Completed(request.id(), new HealingReceipt("bad", badCommand, HealingReceipt.Outcome.REJECTED, 0, 0, 0)));
        assertTrue(bad.state().engine().failure().isPresent()); assertTrue(bad.state().abandoned().isPresent());
    }
    @Test void healingReactionChangesOnlyTheNextIntervalRate() throws Exception {
        var data = json("restoration");
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").add(JsonParser.parseString("""
            {"id":"upgrade","on":"chorus:health_restored","if":{"type":"chorus:event_tag","tag":"chorus_d2:restoration"},"do":[
              {"type":"chorus:grant_buff","buff":"chorus_d2:restoration","tier":{"type":"chorus:constant","value":2,"unit":"count"},
               "duration":{"type":"chorus:constant","value":0.08,"unit":"second"}}]}
            """));
        var test = new Harness(compile(data), EffectState.Mode.PVE);
        test.send(0, "test:restoration", 1, .12); test.send(100_000, "test:remove", 1, 1); test.send(1_000_000, "test:noop", 1, 1);
        assertEquals(List.of(.17500000000000002, .25), test.heals.stream().map(value -> value.command().amount()).toList());
        assertEquals(.425, test.sum(), 1e-12); assertTrue(test.state().buffs().instances().isEmpty());
    }
    @Test void staticSourceRecoveryEndsOnDetachAndZeroRateHasNoSamplingDeadline() {
        var data = JsonParser.parseString("""
            {"version":"test-1","bundles":[{"id":"test:static","health_recovery":[
              {"id":"passive","channel":"test:passive","rate":{"type":"chorus:constant","value":2.5,"unit":"damage_per_second"}}]}]}
            """).getAsJsonObject();
        var program = compile(data); var initial = EffectState.empty().withSource(source("test:static"));
        var commands = new ArrayList<HealingCommand>();
        java.util.function.Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world = request -> {
            var command = (HealingCommand) request.command(); commands.add(command);
            return new HealingReceipt("static/" + commands.size(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
        };
        var session = new EffectSession(engine(program), initial, world);
        session.start(145_001, new RuleEngine.Signal("test:noop", RuleEngine.Empty.INSTANCE));
        assertEquals(3, commands.size()); assertEquals(.145001 * 2.5, commands.stream().mapToDouble(HealingCommand::amount).sum(), 1e-12);
        var detached = session.state().engine().domain().withoutSource("perk");
        new EffectSession(engine(program), detached, world).start(1_000_000, new RuleEngine.Signal("test:noop", RuleEngine.Empty.INSTANCE));
        assertEquals(3, commands.size());
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("health_recovery").get(0).getAsJsonObject().getAsJsonObject("rate").addProperty("value", 0);
        var zero = compile(data); var clock = new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())).withRecovery(zero::recoveryOffers);
        assertEquals(Long.MAX_VALUE, clock.nextDeadline(initial));
    }
    @Test void schemaRejectsWrongUnitsAndDuplicateChannelsEntriesAndRoundTrips() throws Exception {
        var program = load("restoration"); var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow();
        assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        var wrong = json("restoration"); declaration(wrong).add("rate", JsonParser.parseString("""
            {"type":"chorus:constant","value":3,"unit":"damage"}
            """));
        assertThrows(IllegalStateException.class, () -> compile(wrong));
        var negative = json("restoration"); declaration(negative).add("rate", JsonParser.parseString("""
            {"type":"chorus:constant","value":-3,"unit":"damage_per_second"}
            """));
        assertThrows(IllegalStateException.class, () -> compile(negative));
        var duplicate = json("restoration"); duplicate.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("health_recovery").add(declaration(duplicate).deepCopy());
        assertThrows(IllegalStateException.class, () -> compile(duplicate));
        var typo = json("restoration"); declaration(typo).addProperty("prority", 2); assertThrows(IllegalStateException.class, () -> compile(typo));
    }
    private static JsonObject profiled() throws Exception {
        var data = json("restoration"); var recovery = declaration(data);
        recovery.add("rate", recovery.getAsJsonObject("rate").get("of"));
        recovery.addProperty("profile", "test:recovery_rate");
        data.add("profiles", JsonParser.parseString("""
            [{"id":"test:recovery_rate","version":"test-1","input_unit":"damage_per_second","steps":[
              {"type":"chorus:curve","id":"scale","curve":{"type":"chorus:polynomial","coefficients":[0,0.1],"minimum":0,"maximum":100,"boundary":"error"},"output_unit":"damage_per_second"},
              {"type":"chorus:apply","id":"bonuses","operation":"multiply","group":{"name":"bonuses","reduction":"sum"}}]}]
            """));
        data.getAsJsonArray("bundles").add(JsonParser.parseString("""
            {"id":"test:boost","modifiers":[{"id":"double","profile":"test:recovery_rate","stage":"bonuses","group":"bonuses","op":"multiply",
              "value":{"type":"chorus:constant","value":1,"unit":"delta"},"stacking_key":"test:boost",
              "if":{"type":"chorus:event_tag","tag":"chorus_d2:restoration"},
              "reference":"Synthetic recipient modifier","confidence":"assumed"}]}
            """));
        return data;
    }
    @Test void recoveryProfileUsesCurrentRecipientModifiersAndSplitsIntervalsBeforeSourceChanges() throws Exception {
        var program = compile(profiled()); var test = new Harness(program, EffectState.Mode.PVE);
        test.send(0, "test:restoration", 1, .2);
        var boost = new EffectSource("boost", "test:boost", "player", new BuffInstance.Origin("other", "boost", "", ""), Set.of());
        test.session.start(25_000, SourceChange.bind(boost));
        test.session.start(70_001, SourceChange.remove(boost.instance()));
        test.send(200_000, "test:noop", 1, 1);
        assertEquals(.154999 * 3.5 + .045001 * 7, test.sum(), 1e-12);
        assertTrue(test.heals.stream().allMatch(h -> h.command().source().equals(test.source.origin())), "rate query must retain healing origin");
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow();
        assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        var foreign = new Harness(program, EffectState.Mode.PVE);
        foreign.session.start(0, SourceChange.bind(new EffectSource("foreign", "test:boost", "ally", boost.origin(), Set.of())));
        foreign.send(0, "test:restoration", 1, .1); foreign.send(100_000, "test:noop", 1, 1);
        assertEquals(.35, foreign.sum(), 1e-12);
    }
    @Test void recoveryProfileMustExistAndPreserveRateUnitsAndRejectsNegativeOutput() throws Exception {
        var missing = profiled(); missing.remove("profiles"); assertThrows(RuntimeException.class, () -> compile(missing));
        for (String field : List.of("input_unit", "output_unit")) {
            var wrong = profiled(); var profile = wrong.getAsJsonArray("profiles").get(0).getAsJsonObject();
            (field.equals("input_unit") ? profile : profile.getAsJsonArray("steps").get(0).getAsJsonObject()).addProperty(field, "damage");
            assertThrows(RuntimeException.class, () -> compile(wrong));
        }
        var negative = profiled(); negative.getAsJsonArray("profiles").get(0).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject()
                .getAsJsonObject("curve").add("coefficients", JsonParser.parseString("[-1]"));
        var program = compile(negative); var test = new Harness(program, EffectState.Mode.PVE);
        test.send(0, "test:restoration", 1, 1);
        assertThrows(RuntimeException.class, () -> program.recoveryOffers(test.state()));
        assertTrue(test.heals.isEmpty());
    }
}
