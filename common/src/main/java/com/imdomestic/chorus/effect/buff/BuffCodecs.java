package com.imdomestic.chorus.effect.buff;

import static com.imdomestic.chorus.core.codec.DataCodecs.*;
import static com.imdomestic.chorus.effect.buff.BuffDefinition.*;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Set;
import java.util.Map;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.codec.StatCodecs;

/** Resolved buff definitions; rules, modifiers and unresolved value expressions are compiled separately. */
public final class BuffCodecs {
    private BuffCodecs() {}
    private static final Codec<Measure> MEASURE = strict(RecordCodecBuilder.create(i -> i.group(
            FINITE.fieldOf("initial").forGetter(Measure::value), StatCodecs.UNIT.fieldOf("unit").forGetter(Measure::unit)
    ).apply(i, Measure::new)), Set.of("initial", "unit"));
    private record SchemaData(Map<String, Measure> numbers, List<String> sets, List<String> references, List<String> targetSets, List<String> positions, List<String> damageSnapshots) {}
    public static final Codec<BuffSchema> SCHEMA = strict(RecordCodecBuilder.<SchemaData>create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, MEASURE).optionalFieldOf("numbers", Map.of()).forGetter(SchemaData::numbers),
            Codec.STRING.listOf().optionalFieldOf("sets", List.of()).forGetter(SchemaData::sets),
            Codec.STRING.listOf().optionalFieldOf("references", List.of()).forGetter(SchemaData::references),
            Codec.STRING.listOf().optionalFieldOf("target_sets", List.of()).forGetter(SchemaData::targetSets),
            Codec.STRING.listOf().optionalFieldOf("positions", List.of()).forGetter(SchemaData::positions),
            Codec.STRING.listOf().optionalFieldOf("damage_snapshots", List.of()).forGetter(SchemaData::damageSnapshots)
    ).apply(i, SchemaData::new)), Set.of("numbers", "sets", "references", "target_sets", "positions", "damage_snapshots")).flatXmap(data -> safe(() -> new BuffSchema(data.numbers(), Set.copyOf(data.sets()), Set.copyOf(data.references()), Set.copyOf(data.targetSets()), Set.copyOf(data.positions()), Set.copyOf(data.damageSnapshots()))),
            schema -> DataResult.success(new SchemaData(schema.numbers(), schema.sets().stream().sorted().toList(), schema.references().stream().sorted().toList(), schema.targetSets().stream().sorted().toList(), schema.positions().stream().sorted().toList(), schema.damageSnapshots().stream().sorted().toList())));
    private record TimerData(long duration, TimerMode mode, Decay decay, long interval, Refresh refresh, long cap, long rounding) {}
    private static final MapCodec<Timer> TIMER = RecordCodecBuilder.<TimerData>mapCodec(i -> i.group(
            DURATION.fieldOf("duration").forGetter(TimerData::duration),
            enumeration(TimerMode.class).optionalFieldOf("timer_mode", TimerMode.SHARED).forGetter(TimerData::mode),
            enumeration(Decay.class).optionalFieldOf("decay", Decay.ALL).forGetter(TimerData::decay),
            MICROS.optionalFieldOf("decay_interval", 0L).forGetter(TimerData::interval),
            enumeration(Refresh.class).optionalFieldOf("refresh", Refresh.NONE).forGetter(TimerData::refresh),
            DURATION.optionalFieldOf("extension_cap", FOREVER).forGetter(TimerData::cap),
            MICROS.optionalFieldOf("pause_rounding", 0L).forGetter(TimerData::rounding)
    ).apply(i, TimerData::new)).flatXmap(data -> safe(() -> new Timer(data.duration(), data.mode(), data.decay(), data.interval(), data.refresh(), data.cap(), data.rounding())),
            timer -> DataResult.success(new TimerData(timer.durationMicros(), timer.mode(), timer.decay(), timer.decayIntervalMicros(), timer.refresh(), timer.extensionCapMicros(), timer.pauseRoundingMicros())));
    private static final MapCodec<Binding> BINDING = RecordCodecBuilder.mapCodec(i -> i.group(
            enumeration(Attach.class).optionalFieldOf("attach", Attach.HOLDER).forGetter(Binding::attach),
            enumeration(InstanceBy.class).optionalFieldOf("instanced_by", InstanceBy.NONE).forGetter(Binding::instancedBy),
            enumeration(Affects.class).optionalFieldOf("affects", Affects.ALL).forGetter(Binding::affects),
            enumeration(OnStow.class).optionalFieldOf("on_stow", OnStow.KEEP).forGetter(Binding::onStow)
    ).apply(i, Binding::new));
    private record DefinitionData(String id, String version, int maximum, Timer timer, Binding binding, StackMode stackMode,
            boolean highestTier, boolean creditOverflow, List<String> tags, BuffSchema components) {}
    private static final Codec<BuffDefinition> DEFINITION_FIELDS = RecordCodecBuilder.<DefinitionData>create(i -> i.group(
            ID.fieldOf("id").forGetter(DefinitionData::id),
            Codec.STRING.fieldOf("version").forGetter(DefinitionData::version),
            Codec.INT.optionalFieldOf("max_stacks", 1).forGetter(DefinitionData::maximum),
            TIMER.forGetter(DefinitionData::timer), BINDING.forGetter(DefinitionData::binding),
            enumeration(StackMode.class).optionalFieldOf("stack_mode", StackMode.ADD).forGetter(DefinitionData::stackMode),
            Codec.BOOL.optionalFieldOf("keep_highest_tier", false).forGetter(DefinitionData::highestTier),
            Codec.BOOL.optionalFieldOf("credit_overflow", false).forGetter(DefinitionData::creditOverflow),
            ID.listOf().optionalFieldOf("tags", List.of()).forGetter(DefinitionData::tags),
            SCHEMA.optionalFieldOf("components", BuffSchema.EMPTY).forGetter(DefinitionData::components)
    ).apply(i, DefinitionData::new)).flatXmap(data -> safe(() -> new BuffDefinition(data.id(), data.version(), data.maximum(), data.timer(), data.binding(),
            data.stackMode(), data.highestTier(), data.creditOverflow(), Set.copyOf(data.tags()), data.components())), definition -> DataResult.success(new DefinitionData(
            definition.id(), definition.version(), definition.maximumStacks(), definition.timer(), definition.binding(), definition.stackMode(),
            definition.keepHighestTier(), definition.creditOverflow(), definition.tags().stream().sorted().toList(), definition.components())));
    public static final Codec<BuffDefinition> DEFINITION = strict(DEFINITION_FIELDS, Set.of("id", "version", "duration", "max_stacks",
            "timer_mode", "decay", "decay_interval", "refresh", "extension_cap", "pause_rounding", "attach", "instanced_by", "affects",
            "on_stow", "stack_mode", "keep_highest_tier", "credit_overflow", "tags", "components"));
}
