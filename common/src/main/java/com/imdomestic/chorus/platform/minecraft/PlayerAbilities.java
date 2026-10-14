package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Persistent base selection. Current balances and in-flight work belong to the runtime checkpoint. */
public final class PlayerAbilities {
    public interface Owner { PlayerAbilities chorus$abilities(); }
    public static PlayerAbilities get(Player player) { return ((Owner) player).chorus$abilities(); }
    public static final String SAVE_KEY = "chorus:abilities";
    public record Stored(AbilityLoadout loadout, long revision) {
        public Stored { Objects.requireNonNull(loadout); if (revision < 0) throw new IllegalArgumentException("Negative ability revision"); }
        public static final Stored EMPTY = new Stored(AbilityLoadout.EMPTY, 0);
        public static final Codec<Stored> CODEC = com.imdomestic.chorus.core.codec.DataCodecs.strict(RecordCodecBuilder.create(i -> i.group(
                AbilityCodecs.LOADOUT.fieldOf("loadout").forGetter(Stored::loadout),
                Codec.LONG.fieldOf("revision").forGetter(Stored::revision)
        ).apply(i, Stored::new)), Set.of("loadout", "revision"));
    }
    private Stored stored = Stored.EMPTY;
    private Optional<String> inactiveReason = Optional.empty();
    private boolean changing;
    public Stored snapshot() { return stored; }
    public long revision() { return stored.revision(); }
    public boolean configured() { return !stored.loadout().slots().isEmpty() || stored.revision() != 0; }
    public Optional<String> inactiveReason() { return inactiveReason; }
    public void save(ValueOutput output) { if (configured()) output.store(SAVE_KEY, Stored.CODEC, stored); }
    public void load(ValueInput input) {
        if (changing) throw new IllegalStateException("Cannot load abilities during a selection change");
        var next = input.read(SAVE_KEY, Codec.PASSTHROUGH).map(value -> Stored.CODEC.parse(value).getOrThrow()).orElse(Stored.EMPTY);
        stored = next; inactiveReason = Optional.empty();
    }
    AbilityLoadout projection(CompiledEffects program) {
        try {
            program.validateAbilities(stored.loadout()); inactiveReason = Optional.empty(); return stored.loadout();
        } catch (IllegalArgumentException invalid) {
            inactiveReason = Optional.of(invalid.getMessage()); return AbilityLoadout.EMPTY;
        }
    }
    private void check(ServerPlayer player, long expected) {
        if (get(player) != this || !player.level().getServer().isSameThread() || player.isRemoved() || !player.isAlive())
            throw new IllegalStateException("Ability selection belongs to a live server player");
        if (changing) throw new IllegalStateException("Reentrant ability selection change");
        if (revision() != expected) throw new IllegalStateException("Stale ability selection revision");
    }
    /** Trusted selection input. The content host checks subclass, unlock and loadout eligibility. */
    public void configure(ServerPlayer player, AbilityLoadout next, long expectedRevision) {
        check(player, expectedRevision); Objects.requireNonNull(next);
        var runtime = MinecraftEffectRuntime.installed(player.level()).orElse(null);
        if (runtime == null && !next.slots().isEmpty()) throw new IllegalStateException("Ability selection requires an installed ruleset");
        if (runtime != null) {
            runtime.program().validateAbilities(next); runtime.prepareAbilityChange(); check(player, expectedRevision);
        }
        String holder = player.getUUID().toString();
        var before = runtime == null ? AbilityLoadout.EMPTY : runtime.state().engine().domain().abilities().getOrDefault(holder, AbilityLoadout.EMPTY);
        if (stored.loadout().equals(next) && (configured() || before.equals(next))) return;
        var desired = new Stored(next, Math.incrementExact(revision())); changing = true;
        try {
            Runnable save = () -> { stored = desired; inactiveReason = Optional.empty(); };
            if (runtime == null) save.run();
            else runtime.commitPlayerAbilities(player, new AbilityChange(holder, before, next), save);
        } finally { changing = false; }
    }
    /** Base selections survive actual player replacement independently of equipment drops. */
    public void transferFrom(PlayerAbilities previous) {
        if (previous == this) return;
        if (changing || previous.changing || configured()) throw new IllegalStateException("Invalid ability selection transfer");
        if (!previous.configured()) return;
        long revision = Math.incrementExact(previous.revision());
        stored = new Stored(previous.stored.loadout(), revision); inactiveReason = Optional.empty();
        previous.stored = new Stored(AbilityLoadout.EMPTY, revision); previous.inactiveReason = Optional.empty();
    }
}
