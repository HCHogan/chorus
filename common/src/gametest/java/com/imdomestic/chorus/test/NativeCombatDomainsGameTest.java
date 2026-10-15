package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.*;
import static com.imdomestic.chorus.test.ResourceRuntimeGameTest.domain;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;

/** Real hurt calls across three installed native domains, with independently restored participant facets. */
public class NativeCombatDomainsGameTest {
    private static final Map<ServerLevel, Map<ChunkPos, ChunkLease>> CHUNKS = new IdentityHashMap<>();
    private static final class ChunkLease {
        final boolean added; int references = 1;
        ChunkLease(boolean added) { this.added = added; }
    }
    /** Loading terrain alone does not make a remote level's entity sections visible or ticking. */
    static void inReadyRemoteChunks(GameTestHelper h, Runnable body) {
        var remote = Objects.requireNonNull(h.getLevel().getServer().getLevel(Level.NETHER));
        var position = h.absolutePos(new BlockPos(2, 3, 2));
        var chunks = new LinkedHashSet<>(List.of(ChunkPos.containing(position), ChunkPos.containing(position.offset(3, 0, 0))));
        var leases = CHUNKS.computeIfAbsent(remote, _ -> new HashMap<>());
        for (var chunk : chunks) {
            var existing = leases.get(chunk);
            if (existing == null) leases.put(chunk, new ChunkLease(remote.setChunkForced(chunk.x(), chunk.z(), true)));
            else existing.references++;
        }
        boolean[] released = {false};
        Runnable release = () -> {
            if (released[0]) return;
            released[0] = true;
            for (var chunk : chunks) {
                var lease = leases.get(chunk);
                if (--lease.references == 0) {
                    leases.remove(chunk);
                    if (lease.added) remote.setChunkForced(chunk.x(), chunk.z(), false);
                }
            }
            if (leases.isEmpty()) CHUNKS.remove(remote);
        };
        h.runBeforeTestEnd(release);
        // GameTestInfo removes a scheduled Runnable after invoking it, so rescheduling the same
        // Runnable from itself loses that next invocation. Sequences explicitly retry assertions.
        h.startSequence().thenWaitUntil(() -> {
            for (var chunk : chunks) h.assertTrue(remote.areEntitiesActuallyLoadedAndTicking(chunk),
                    "Remote native combat chunk must have loaded, ticking entity sections: " + chunk
                            + " loaded=" + remote.areEntitiesLoaded(chunk.pack()));
        }).thenExecute(() -> { try { body.run(); } finally { release.run(); } });
    }
    static CompiledEffects program() {
        return compile(object("""
            {"version":"native-combat-domains",
             "resources":[{"id":"test:energy","capacity":1,"initial":0.5}],
             "profiles":[{"id":"test:attack","version":"native-combat-domains","input_unit":"damage",
               "steps":[{"type":"chorus:apply","id":"bonus","operation":"multiply","group":{"name":"bonus","reduction":"sum"}}]}],
             "buffs":[
               {"definition":{"id":"test:next_hit","version":"native-combat-domains","duration":"permanent","max_stacks":3},
                "bundle":"test:next_hit","consume_on_damage":{"when":"hit"}},
               {"definition":{"id":"test:shield","version":"native-combat-domains","duration":"permanent",
                 "components":{"numbers":{"capacity":{"initial":10,"unit":"damage"}}}},"shield":{"capacity":"capacity"}}],
             "bundles":[
               {"id":"test:source",
                "rules":[
                  {"id":"batch","on":"test:batch","do":[{"type":"chorus:play_cue","cue":"test:batch"}]},
                  {"id":"managed","on":"test:managed","do":[{"type":"chorus:damage","damage_type":"chorus_gametest:delayed",
                    "amount":{"type":"chorus:constant","value":10,"unit":"damage"},"scaling_profile":"test:attack"}]},
                  {"id":"hit","on":"chorus:hit","binding":"origin_bundle","do":[{"type":"chorus:play_cue","cue":"test:hit"}]},
                  {"id":"ended","on":"chorus:buff_ended","do":[{"type":"chorus:play_cue","cue":"test:buff_ended"}]}],
                "modifiers":[{"id":"source_bonus","profile":"test:attack","stage":"bonus","group":"bonus","op":"multiply",
                 "stacking_key":"test:source_bonus","value":{"type":"chorus:constant","value":0.5,"unit":"delta"},
                 "reference":"Synthetic independent native source fixture","confidence":"assumed"}]},
               {"id":"test:next_hit","scope":"buff",
                "modifiers":[{"id":"buff_bonus","profile":"test:attack","stage":"bonus","group":"bonus","op":"multiply",
                 "stacking_key":"test:buff_bonus","evaluate":"on_hit","value":{"type":"chorus:by_stacks","values":[0.5,1,1.5],"unit":"delta"},
                 "reference":"Synthetic shared native reservation fixture","confidence":"assumed"}]}]}
            """));
    }
    static String id(LivingEntity entity) { return entity.getUUID().toString(); }
    static void sturdy(LivingEntity entity) {
        entity.setNoGravity(true); entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); entity.setHealth(1000);
        entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
    }
    static LivingEntity spawn(ServerLevel level, BlockPos position) {
        level.getChunk(position);
        var entity = Objects.requireNonNull(EntityTypes.COW.create(level, EntitySpawnReason.COMMAND));
        entity.setPos(position.getX() + .5, position.getY(), position.getZ() + .5); entity.setNoAi(true); sturdy(entity);
        if (!level.addFreshEntity(entity) || level.getEntity(entity.getUUID()) != entity) {
            entity.discard(); throw new IllegalStateException("Native combat fixture entity was not UUID-indexed in " + domain(level));
        }
        return entity;
    }
    static int stacks(EffectState state, String holder) {
        return state.buffs().instances().values().stream().filter(buff -> buff.key().holder().equals(holder) && buff.definition().id().equals("test:next_hit"))
                .mapToInt(BuffInstance::count).sum();
    }
    static double capacity(EffectState state, String holder) {
        return state.buffs().instances().values().stream().filter(buff -> buff.key().holder().equals(holder) && buff.definition().id().equals("test:shield"))
                .findFirst().orElseThrow().components().numbers().get("capacity");
    }
    static final class Fixture implements AutoCloseable {
        final GameTestHelper h; final ServerLevel a, b, c; final LivingEntity owner, target, child;
        final CompiledEffects program = program(); final EffectSource source; final DamageSource damage;
        final MinecraftResourceRuntime host; final Map<ServerLevel, MinecraftEffectRuntime> runtimes = new IdentityHashMap<>(); final List<DamageReceipt> hits = new ArrayList<>();
        boolean loseCallback, failHitCue; int callbacks, completedRoots, managedCalls; final boolean shield; final int initialStacks;
        final List<String> cues = new ArrayList<>(); Runnable betweenRoots = () -> {};
        Fixture(GameTestHelper h, boolean shield) { this(h, shield, 2); }
        Fixture(GameTestHelper h, boolean shield, int initialStacks) {
            this.h = h; this.shield = shield; this.initialStacks = initialStacks; b = h.getLevel();
            a = Objects.requireNonNull(b.getServer().getLevel(Level.NETHER)); c = Objects.requireNonNull(b.getServer().getLevel(Level.END));
            var position = h.absolutePos(new BlockPos(2, 3, 2));
            owner = spawn(a, new BlockPos(position.getX(), 90, position.getZ()));
            child = spawn(a, new BlockPos(position.getX() + 3, 90, position.getZ()));
            target = h.spawnWithNoFreeWill(EntityTypes.COW, 3, 3, 3); sturdy(target);
            h.assertTrue(a.getEntity(owner.getUUID()) == owner && a.getEntity(child.getUUID()) == child && b.getEntity(target.getUUID()) == target,
                    "fixture requires all three actual bodies in their physical UUID indexes");
            var origin = new BuffInstance.Origin(id(owner), "native-combat-source", "", "");
            source = new EffectSource("native-combat-source", "test:source", id(owner), origin, Set.of());
            var type = b.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            damage = new DamageSource(type, null, owner);
            try {
                var ownerState = EffectState.empty().withSource(source);
                ownerState = ownerState.withBuffs(Buffs.grant(ownerState.buffs(), program.buff("test:next_hit"), id(owner), id(owner), origin, initialStacks, 1, BuffDefinition.FOREVER).store());
                var resourceState = EffectState.empty().withResource(new ResourceState(key(), .5, 1, 0));
                if (shield) resourceState = resourceState.withBuffs(Buffs.grant(resourceState.buffs(), program.buff("test:shield"), id(child), id(owner), origin, 1, 1, BuffDefinition.FOREVER).store());
                var states = Map.of(domain(a), ownerState, domain(b), EffectState.empty(), domain(c), resourceState);
                var directory = ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY, id(owner), domain(c), program, resourceState);
                var placement = ParticipantPlacement.adopt(ParticipantPlacement.Directory.EMPTY,
                        Map.of(id(owner), domain(a), id(target), domain(b), id(child), domain(a)),
                        Map.of(id(owner), domain(a), id(target), domain(b), id(child), domain(c)), states);
                var effects = new TreeMap<String, EffectSession.Checkpoint>();
                var dimensions = new TreeMap<String, MinecraftEffectRuntime.Checkpoint>();
                var policies = new TreeMap<String, MinecraftResourceRuntime.RestoreBinding>();
                for (var level : List.of(a, b, c)) {
                    String domain = domain(level);
                    var saved = new EffectSession(program, clock(), 256, states.get(domain), _ -> { throw new IllegalStateException("Detached fixture session executed"); }).checkpoint();
                    effects.put(domain, saved);
                    dimensions.put(domain, new MinecraftEffectRuntime.Checkpoint(domain, level.getGameTime(), saved, Set.of(), Set.of(), Set.of()));
                    policies.put(domain, new MinecraftResourceRuntime.RestoreBinding(level, program, clock(), this::world,
                            (victim, nativeSource, amount) -> new DamageCommand(id(victim), source.origin(), amount, "chorus_gametest:delayed", Set.of(), Set.of(), false, Optional.of("test:attack"))));
                }
                var checkpoint = new MinecraftResourceRuntime.Checkpoint(new ResourceSessionAuthority.Checkpoint(directory, effects, placement), dimensions);
                host = MinecraftResourceRuntime.restore(b.getServer(), checkpoint, policies);
                for (var level : List.of(a, b, c)) runtimes.put(level, host.runtime(level));
            } catch (RuntimeException | Error failure) {
                owner.discard(); target.discard(); child.discard(); throw failure;
            }
        }
        ResourceState.Key key() { return new ResourceState.Key(id(owner), "test:energy"); }
        EffectState state(ServerLevel level) { return runtimes.get(level).state().engine().domain(); }
        DamageReceipt hit(LivingEntity victim) {
            victim.damageCooldownTime = 0;
            var receipt = MinecraftDamageExecutor.execute(UUID.randomUUID().toString(), victim, damage, 10, false);
            completedRoots++; return receipt;
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            if (request.command() instanceof DamageCommand command) {
                managedCalls++;
                LivingEntity victim = command.target().equals(id(target)) ? target : child;
                victim.damageCooldownTime = 0;
                var actions = new MinecraftWorldActions((ServerLevel) victim.level(), key -> key.equals(id(victim)) ? victim : null,
                        _ -> damage, (_, _) -> true, _ -> {});
                var receipt = (DamageReceipt) actions.apply(request); hits.add(receipt); completedRoots++; return receipt;
            }
            if (!(request.command() instanceof Action.CueCommand cue)) throw new IllegalStateException("Unexpected combat fixture world command: " + request.command());
            if (!cue.cue().equals("test:batch")) {
                cues.add(cue.cue());
                if (failHitCue && cue.cue().equals("test:hit")) throw new IllegalStateException("Injected failure while draining an already committed native hit");
                return RuleEngine.Empty.INSTANCE;
            }
            callbacks++;
            hits.add(hit(target));
            h.assertValueEqual(stacks(state(a), id(owner)), initialStacks, "first root published before the managed callback returned");
            betweenRoots.run(); hits.add(hit(child));
            h.assertValueEqual(stacks(state(a), id(owner)), initialStacks, "second root published before the managed callback returned");
            if (loseCallback) throw new IllegalStateException("Injected loss after two real native roots");
            return RuleEngine.Empty.INSTANCE;
        }
        void batch() { runtimes.get(a).start(new RuleEngine.Signal("test:batch", new EffectEvent(id(owner), id(target), source.origin(), Set.of(), Map.of()))); }
        void managed() { runtimes.get(a).start(new RuleEngine.Signal("test:managed", new EffectEvent(id(owner), id(target), source.origin(), Set.of(), Map.of()))); }
        void healthy() {
            host.drain(32);
            for (var level : List.of(a, b, c)) {
                var runtime = runtimes.get(level);
                h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "native combat domain failed: " + domain(level) + " " + runtime.failure());
            }
            h.assertTrue(host.combatFailure().isEmpty(), "host retained failed native combat"); host.checkpoint();
        }
        @Override public void close() {
            try { host.close(); } finally { owner.discard(); target.discard(); child.discard(); }
        }
    }

    @GameCase(environment="chorus_gametest:resource_runtime",maxTicks=80)
    public void remoteParticipantsRemainAfterResourceOnlyTransfer(GameTestHelper h) {
        inReadyRemoteChunks(h, () -> {
            try (var f = new Fixture(h, false)) {
                near(h, f.hit(f.target).healthLoss(), 25, "physical B hit reads source and two Buff stacks from A");
                h.assertValueEqual(stacks(f.state(f.a), id(f.owner)), 1, "remote Buff consumed exactly once");
                f.host.transfer(id(f.owner), f.b);
                h.assertValueEqual(f.host.snapshot().directory().owner(id(f.owner)).lease().domain(), domain(f.b), "resource-only transfer did not move account");
                h.assertValueEqual(f.host.participants().owner(ParticipantPlacement.Facet.CHARACTER, id(f.owner)).domain(), domain(f.a), "resource transfer moved character facet");
                h.assertValueEqual(f.host.participants().owner(ParticipantPlacement.Facet.BUFFS, id(f.owner)).domain(), domain(f.a), "resource transfer moved Buff facet");
                near(h, f.hit(f.target).healthLoss(), 20, "resource transfer preserved remote remaining Buff and source bonus");
                near(h, f.hit(f.target).healthLoss(), 15, "remote character modifier remains after Buff consumption");
                near(h, f.target.getHealth(), 940, "three physical hits applied once");
                h.assertValueEqual(stacks(f.state(f.a), id(f.owner)), 0, "one-use Buff completely consumed"); f.healthy();
            } h.succeed();
        });
    }

    @GameCase(environment="chorus_gametest:resource_runtime",maxTicks=80)
    public void nestedRemoteChildSurvivesUnknownParentWithSharedReservation(GameTestHelper h) {
        inReadyRemoteChunks(h, () -> {
            try (var f = new Fixture(h, true)) {
                String tag = "cross-domain-parent/" + UUID.randomUUID(); f.target.addTag(tag);
                TestDamageHooks.ALLOW_DAMAGE.register((victim, source, amount) -> {
                    if (!victim.entityTags().contains(tag)) return true;
                    victim.removeTag(tag); f.hits.add(f.hit(f.child));
                    h.assertValueEqual(stacks(f.state(f.a), id(f.owner)), 2, "nested child escaped parent publication boundary");
                    throw new IllegalStateException("Injected parent failure after cross-domain child and shield writes");
                });
                refused(h, IllegalStateException.class, () -> f.hit(f.target));
                var child = f.hits.getFirst(); near(h, child.outgoing().orElseThrow().output().value(), 20, "child sees one stack reserved by its remote parent");
                near(h, child.shieldLoss(), 10, "child spent its shield stored in C"); near(h, child.healthLoss(), 10, "remaining child damage reached actual A entity");
                near(h, f.target.getHealth(), 1000, "unknown parent did not reach health"); near(h, f.child.getHealth(), 990, "actual child result retained");
                var failure = f.host.combatFailure().orElseThrow(); h.assertTrue(failure.published(), "confirmed remote writes were only diagnostic, not published");
                h.assertValueEqual(failure.pending().size(), 1, "unknown parent reservation missing");
                h.assertValueEqual(failure.pending().getFirst().domain(), domain(f.b), "unknown parent physical domain changed");
                h.assertValueEqual(failure.completed().get(domain(f.a)).size(), 1, "confirmed child evidence missing");
                h.assertValueEqual(stacks(f.state(f.a), id(f.owner)), 1, "known child consumption was rolled back");
                near(h, capacity(f.state(f.c), id(f.child)), 0, "known remote shield write was rolled back");
                refused(h, IllegalStateException.class, f.host::checkpoint);
            } h.succeed();
        });
    }

    @GameCase(environment="chorus_gametest:resource_runtime",maxTicks=80)
    public void multipleNativeRootsCommitAfterOneManagedCallback(GameTestHelper h) {
        inReadyRemoteChunks(h, () -> {
            try (var f = new Fixture(h, false)) {
                f.batch(); h.assertValueEqual(f.callbacks, 1, "managed batch callback executed more than once");
                h.assertValueEqual(f.completedRoots, 2, "managed batch lost a native root");
                near(h, f.hits.getFirst().healthLoss(), 25, "first native root used both charges");
                near(h, f.hits.getLast().healthLoss(), 20, "second native root saw first root's confirmed consumption");
                near(h, f.target.getHealth(), 975, "first real target health"); near(h, f.child.getHealth(), 980, "second real target health");
                h.assertValueEqual(stacks(f.state(f.a), id(f.owner)), 0, "joint publication lost a native root's consumption"); f.healthy();
            } h.succeed();
        });
    }

    @GameCase(environment="chorus_gametest:resource_runtime",maxTicks=80)
    public void lostManagedCallbackRetainsBothNativeRootsWithoutReceipt(GameTestHelper h) {
        inReadyRemoteChunks(h, () -> {
            try (var f = new Fixture(h, false)) {
                f.loseCallback = true; refused(h, IllegalStateException.class, f::batch);
                near(h, f.target.getHealth(), 975, "unknown callback rolled back first actual hit"); near(h, f.child.getHealth(), 980, "unknown callback rolled back second actual hit");
                var failure = f.host.combatFailure().orElseThrow(); h.assertTrue(failure.published(), "known multi-root writes were not jointly retained");
                h.assertTrue(failure.pending().isEmpty(), "known native roots became unknown when callback receipt was lost");
                h.assertValueEqual(failure.completed().values().stream().mapToInt(List::size).sum(), 2, "multi-root failure evidence lost a known receipt");
                h.assertValueEqual(stacks(f.state(f.a), id(f.owner)), 0, "known two-root consumption was rolled back");
                h.assertTrue(f.runtimes.get(f.a).state().engine().pending().isPresent(), "unknown managed callback received a fabricated completion");
                refused(h, IllegalStateException.class, f.host::checkpoint);
                h.assertValueEqual(f.callbacks, 1, "unknown callback retried"); h.assertValueEqual(f.completedRoots, 2, "unknown native hit retried");
            } h.succeed();
        });
    }

    @GameCase(environment="chorus_gametest:resource_runtime",maxTicks=80)
    public void managedPrimaryAcrossDomainsPublishesDamageAndConsumptionFactsOnce(GameTestHelper h) {
        inReadyRemoteChunks(h, () -> {
            try (var f = new Fixture(h, false, 1)) {
                f.managed(); f.healthy();
                h.assertValueEqual(f.managedCalls, 1, "one actual managed DamageCommand");
                h.assertValueEqual(f.hits.size(), 1, "managed primary produced duplicate receipts");
                var hit = f.hits.getFirst(); h.assertTrue(hit.consumptionSettled(), "managed primary lost its consumption settlement marker");
                near(h, hit.healthLoss(), 20, "managed primary reads remote source and its final Buff stack");
                near(h, f.target.getHealth(), 980, "managed primary applied exactly once");
                h.assertValueEqual(stacks(f.state(f.a), id(f.owner)), 0, "Action.complete consumed the native result again");
                h.assertValueEqual(f.cues, List.of("test:hit", "test:buff_ended"), "managed primary damage precedes one final Buff lifecycle fact");
            } h.succeed();
        });
    }

    @GameCase(environment="chorus_gametest:resource_runtime",maxTicks=80)
    public void removedSourceBodyKeepsHistoricalCreditAcrossNativeRoots(GameTestHelper h) {
        inReadyRemoteChunks(h, () -> {
            try (var f = new Fixture(h, false)) {
                String life = MinecraftEntityLife.get(f.owner).id();
                f.betweenRoots = () -> { f.owner.discard(); h.assertTrue(f.a.getEntity(f.owner.getUUID()) == null, "removed source remained indexed"); };
                f.batch(); f.healthy();
                h.assertValueEqual(MinecraftEntityLife.get(f.owner).id(), life, "ordinary removal changed historical life identity");
                h.assertValueEqual(f.completedRoots, 2, "removed source blocked the next root's existing credit");
                near(h, f.target.getHealth(), 975, "first native root"); near(h, f.child.getHealth(), 980, "second native root retained source credit");
                h.assertValueEqual(stacks(f.state(f.a), id(f.owner)), 0, "removed source lost known Buff consumption");
            } h.succeed();
        });
    }

    @GameCase(environment="chorus_gametest:resource_runtime",maxTicks=80)
    public void replacementSourceBodyCannotJoinAnAlreadyCapturedNativeBoundary(GameTestHelper h) {
        inReadyRemoteChunks(h, () -> {
            LivingEntity[] replacement = {null};
            try (var f = new Fixture(h, false)) {
                f.betweenRoots = () -> {
                    var old = f.owner; old.discard();
                    var next = Objects.requireNonNull(EntityTypes.COW.create(f.a, EntitySpawnReason.COMMAND)); replacement[0] = next;
                    next.setUUID(old.getUUID()); next.setId(old.getId()); next.setPos(old.position()); next.setNoAi(true); sturdy(next);
                    h.assertTrue(f.a.addFreshEntity(next) && f.a.getEntity(old.getUUID()) == next, "replacement source was not indexed");
                    h.assertTrue(!MinecraftEntityLife.get(next).id().equals(MinecraftEntityLife.get(old).id()), "replacement reused historical native life");
                };
                refused(h, IllegalStateException.class, f::batch);
                near(h, f.target.getHealth(), 975, "known first root was rolled back"); near(h, f.child.getHealth(), 1000, "second root hurt with a replaced source body");
                h.assertValueEqual(f.completedRoots, 1, "replacement body reached native hurt");
                var failure = f.host.combatFailure().orElseThrow(); h.assertTrue(failure.published(), "replacement failure discarded the first actual root");
                h.assertValueEqual(failure.completed().values().stream().mapToInt(List::size).sum(), 1, "replacement failure lost the confirmed first root");
                h.assertValueEqual(stacks(f.state(f.a), id(f.owner)), 1, "replacement failure rolled back known consumption");
                refused(h, IllegalStateException.class, f.host::checkpoint);
            } finally { if (replacement[0] != null) replacement[0].discard(); }
            h.succeed();
        });
    }

    @GameCase(environment="chorus_gametest:resource_runtime",maxTicks=80)
    public void swallowedPostDamageMutationRetainsActualReceiptAndConsumption(GameTestHelper h) {
        inReadyRemoteChunks(h, () -> {
            try (var f = new Fixture(h, false)) {
                String tag = "cross-domain-after-hurt/" + UUID.randomUUID(); f.target.addTag(tag); int[] rejected = {0};
                TestDamageHooks.AFTER_DAMAGE.register((victim, source, original, inflicted, blocked) -> {
                    if (!victim.entityTags().contains(tag)) return;
                    victim.removeTag(tag);
                    near(h, victim.getHealth(), 975, "post-damage callback did not observe actual health loss");
                    try { f.host.grant(f.key(), .1, .1); }
                    catch (IllegalStateException expected) { rejected[0]++; }
                });
                refused(h, IllegalStateException.class, () -> f.hit(f.target));
                h.assertValueEqual(rejected[0], 1, "post-damage authority mutation was not rejected");
                near(h, f.target.getHealth(), 975, "swallowed callback violation rolled back actual native health");
                near(h, f.state(f.c).resources().get(f.key()).value(), .5, "rejected resource mutation changed its balance");
                h.assertValueEqual(stacks(f.state(f.a), id(f.owner)), 1, "actual receipt did not settle one remote consumption");
                var failure = f.host.combatFailure().orElseThrow(); h.assertTrue(failure.published(), "actual receipt repair was not published");
                h.assertTrue(failure.pending().isEmpty(), "known post-damage receipt stayed pending");
                h.assertValueEqual(failure.completed().values().stream().mapToInt(List::size).sum(), 1, "post-damage actual receipt disappeared");
                for (var runtime : f.runtimes.values()) h.assertTrue(runtime.failure().isPresent(), "post-damage failure did not stop every domain");
                refused(h, IllegalStateException.class, f.host::checkpoint);
            } h.succeed();
        });
    }

    @GameCase(environment="chorus_gametest:resource_runtime",maxTicks=80)
    public void failedPostCommitReactionStopsTheWholeHostWithoutLosingCombat(GameTestHelper h) {
        inReadyRemoteChunks(h, () -> {
            try (var f = new Fixture(h, false)) {
                f.failHitCue = true; refused(h, IllegalStateException.class, () -> f.hit(f.target));
                near(h, f.target.getHealth(), 975, "reaction failure rolled back committed native health");
                h.assertValueEqual(stacks(f.state(f.a), id(f.owner)), 1, "reaction failure rolled back committed remote consumption");
                h.assertValueEqual(f.cues, List.of("test:hit"), "failed post-commit reaction replayed or was skipped");
                var failure = f.host.combatFailure().orElseThrow(); h.assertTrue(failure.published(), "cleared native boundary forgot committed publication evidence");
                h.assertTrue(failure.pending().isEmpty(), "post-commit reaction made completed native damage pending");
                h.assertValueEqual(failure.completed().values().stream().mapToInt(List::size).sum(), 1, "post-commit failure lost its native receipt");
                for (var runtime : f.runtimes.values()) h.assertTrue(runtime.failure().isPresent(), "post-commit drain failure did not stop every domain");
                refused(h, IllegalStateException.class, f.host::checkpoint);
            } h.succeed();
        });
    }

}
