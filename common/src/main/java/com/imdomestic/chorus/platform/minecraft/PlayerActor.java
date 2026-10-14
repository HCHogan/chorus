package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.actor.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Persistent, server-owned standing configuration; abilities, equipment and temporary effects are separate. */
public final class PlayerActor {
    public interface Owner { PlayerActor chorus$actor(); }
    public static PlayerActor get(Player player) { return ((Owner) player).chorus$actor(); }
    public static final String SAVE_KEY = "chorus:actor";
    public record Stored(Optional<ActorBinding> binding, long revision) {
        public Stored { Objects.requireNonNull(binding); if (revision < 0) throw new IllegalArgumentException("Negative actor revision"); }
        public static final Stored EMPTY = new Stored(Optional.empty(), 0);
        public static final Codec<Stored> CODEC = com.imdomestic.chorus.core.codec.DataCodecs.strict(RecordCodecBuilder.create(i -> i.group(
                ActorCodecs.BINDING.optionalFieldOf("binding").forGetter(Stored::binding),
                Codec.LONG.fieldOf("revision").forGetter(Stored::revision)
        ).apply(i, Stored::new)), Set.of("binding", "revision"));
    }
    private Stored stored = Stored.EMPTY;
    private Optional<String> inactiveReason = Optional.empty();
    private boolean changing;
    public Stored snapshot() { return stored; }
    public long revision() { return stored.revision(); }
    public boolean configured() { return stored.binding().isPresent() || stored.revision() != 0; }
    public Optional<String> inactiveReason() { return inactiveReason; }
    public void save(ValueOutput output) { if (configured()) output.store(SAVE_KEY, Stored.CODEC, stored); }
    public void load(ValueInput input) {
        if (changing) throw new IllegalStateException("Cannot load actor during a configuration change");
        // Present malformed data must not be mistaken for an intentionally empty configuration.
        var next = input.read(SAVE_KEY, Codec.PASSTHROUGH).map(value -> Stored.CODEC.parse(value).getOrThrow()).orElse(Stored.EMPTY);
        stored = next; inactiveReason = Optional.empty();
    }
    Optional<ActorBinding> projection(CompiledEffects program, String holder) {
        try {
            stored.binding().ifPresent(value -> program.validateSource(ActorSources.source(holder, value)));
            inactiveReason = Optional.empty(); return stored.binding();
        } catch (IllegalArgumentException invalid) {
            inactiveReason = Optional.of(invalid.getMessage()); return Optional.empty();
        }
    }
    private void check(ServerPlayer player, long expected) {
        if (get(player) != this || !player.level().getServer().isSameThread() || player.isRemoved() || !player.isAlive())
            throw new IllegalStateException("Actor configuration belongs to a live server player");
        if (changing) throw new IllegalStateException("Reentrant actor configuration change");
        if (revision() != expected) throw new IllegalStateException("Stale actor configuration revision");
    }
    /** Trusted progression/configuration input; there is no client request granting arbitrary bundles. */
    public void configure(ServerPlayer player, Optional<ActorBinding> next, long expectedRevision) {
        check(player, expectedRevision); Objects.requireNonNull(next);
        var runtime = MinecraftEffectRuntime.installed(player.level()).orElse(null);
        if (runtime == null && next.isPresent()) throw new IllegalStateException("Actor configuration requires an installed ruleset");
        String holder = player.getUUID().toString();
        if (runtime != null) {
            next.ifPresent(value -> runtime.program().validateSource(ActorSources.source(holder, value)));
            runtime.prepareActorChange(); check(player, expectedRevision);
        }
        if (stored.binding().equals(next)) return;
        var desired = new Stored(next, Math.incrementExact(revision()));
        changing = true;
        try {
            Runnable save = () -> { stored = desired; inactiveReason = Optional.empty(); };
            if (runtime == null) save.run();
            else {
                var before = Optional.ofNullable(runtime.state().engine().domain().actors().get(holder));
                runtime.commitPlayerActor(player, new ActorChange(holder, before, next), save);
            }
        } finally { changing = false; }
    }
    /** Persistent configuration moves to the replacement player independently of keepInventory. */
    public void transferFrom(PlayerActor previous) {
        if (previous == this) return;
        if (changing || previous.changing || configured()) throw new IllegalStateException("Invalid actor configuration transfer");
        if (!previous.configured()) return;
        long revision = Math.incrementExact(previous.revision());
        stored = new Stored(previous.stored.binding(), revision); inactiveReason = Optional.empty();
        previous.stored = new Stored(Optional.empty(), revision); previous.inactiveReason = Optional.empty();
    }
}
