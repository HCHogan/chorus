package com.imdomestic.chorus.effect.equipment;

import static com.imdomestic.chorus.core.codec.DataCodecs.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.codec.StatCodecs;

public final class EquipmentCodecs {
    private EquipmentCodecs() {}
    private static final Codec<Set<String>> IDS = ID.listOf().xmap(Set::copyOf, set -> set.stream().sorted().toList());
    public static final Codec<Measure> MEASURE = strict(RecordCodecBuilder.create(i -> i.group(
            FINITE.fieldOf("value").forGetter(Measure::value), StatCodecs.UNIT.fieldOf("unit").forGetter(Measure::unit)
    ).apply(i, Measure::new)), Set.of("value", "unit"));
    private static final Codec<EquipmentSchema.Parameter> PARAMETER = strict(RecordCodecBuilder.create(i -> i.group(
            StatCodecs.UNIT.fieldOf("unit").forGetter(EquipmentSchema.Parameter::unit), FINITE.fieldOf("minimum").forGetter(EquipmentSchema.Parameter::minimum),
            FINITE.fieldOf("maximum").forGetter(EquipmentSchema.Parameter::maximum), Codec.BOOL.optionalFieldOf("integral", false).forGetter(EquipmentSchema.Parameter::integral)
    ).apply(i, EquipmentSchema.Parameter::new)), Set.of("unit", "minimum", "maximum", "integral"));
    private static final Codec<EquipmentSchema.Slot> SLOT = strict(RecordCodecBuilder.create(i -> i.group(
            ID.fieldOf("id").forGetter(EquipmentSchema.Slot::id), IDS.fieldOf("accepts").forGetter(EquipmentSchema.Slot::accepts),
            Codec.BOOL.optionalFieldOf("weapon", false).forGetter(EquipmentSchema.Slot::weapon)
    ).apply(i, EquipmentSchema.Slot::new)), Set.of("id", "accepts", "weapon"));
    private static final Codec<EquipmentSchema.Effect> EFFECT = strict(RecordCodecBuilder.create(i -> i.group(
            ID.fieldOf("bundle").forGetter(EquipmentSchema.Effect::bundle), IDS.optionalFieldOf("tags", Set.of()).forGetter(EquipmentSchema.Effect::tags),
            enumeration(EquipmentSchema.Activation.class).optionalFieldOf("activation", EquipmentSchema.Activation.EQUIPPED).forGetter(EquipmentSchema.Effect::activation),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("parameters", Map.of()).forGetter(EquipmentSchema.Effect::parameters)
    ).apply(i, EquipmentSchema.Effect::new)), Set.of("bundle", "tags", "activation", "parameters"));
    private static final Codec<EquipmentSchema.Socket> SOCKET = strict(RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, EFFECT).fieldOf("options").forGetter(EquipmentSchema.Socket::options),
            Codec.BOOL.optionalFieldOf("required", true).forGetter(EquipmentSchema.Socket::required)
    ).apply(i, EquipmentSchema.Socket::new)), Set.of("options", "required"));
    private static final Codec<EquipmentSchema.Item> ITEM = strict(RecordCodecBuilder.create(i -> i.group(
            ID.fieldOf("id").forGetter(EquipmentSchema.Item::id), IDS.fieldOf("tags").forGetter(EquipmentSchema.Item::tags),
            Codec.unboundedMap(Codec.STRING, EFFECT).optionalFieldOf("effects", Map.of()).forGetter(EquipmentSchema.Item::effects),
            Codec.unboundedMap(Codec.STRING, SOCKET).optionalFieldOf("sockets", Map.of()).forGetter(EquipmentSchema.Item::sockets),
            Codec.unboundedMap(Codec.STRING, PARAMETER).optionalFieldOf("parameters", Map.of()).forGetter(EquipmentSchema.Item::parameters)
    ).apply(i, EquipmentSchema.Item::new)), Set.of("id", "tags", "effects", "sockets", "parameters"));
    private static final Codec<EquipmentSchema.Limit> LIMIT = strict(RecordCodecBuilder.create(i -> i.group(
            ID.fieldOf("id").forGetter(EquipmentSchema.Limit::id), ID.fieldOf("tag").forGetter(EquipmentSchema.Limit::tag),
            IDS.optionalFieldOf("slots", Set.of()).forGetter(EquipmentSchema.Limit::slots), Codec.INT.fieldOf("maximum").forGetter(EquipmentSchema.Limit::maximum)
    ).apply(i, EquipmentSchema.Limit::new)), Set.of("id", "tag", "slots", "maximum"));
    public static final Codec<EquipmentSchema> SCHEMA = strict(RecordCodecBuilder.create(i -> i.group(
            SLOT.listOf().optionalFieldOf("slots", List.of()).forGetter(EquipmentSchema::slots),
            ITEM.listOf().optionalFieldOf("items", List.of()).forGetter(EquipmentSchema::items),
            LIMIT.listOf().optionalFieldOf("limits", List.of()).forGetter(EquipmentSchema::limits),
            ID.optionalFieldOf("presentation").forGetter(EquipmentSchema::presentation)
    ).apply(i, EquipmentSchema::new)), Set.of("slots", "items", "limits", "presentation"));
    public static final Codec<Loadout.Gear> GEAR = strict(RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("instance").forGetter(Loadout.Gear::instance), ID.fieldOf("definition").forGetter(Loadout.Gear::definition),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("choices", Map.of()).forGetter(Loadout.Gear::choices),
            Codec.unboundedMap(Codec.STRING, MEASURE).optionalFieldOf("parameters", Map.of()).forGetter(Loadout.Gear::parameters)
    ).apply(i, Loadout.Gear::new)), Set.of("instance", "definition", "choices", "parameters"));
    public static final Codec<Loadout> LOADOUT = strict(RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(ID, GEAR).optionalFieldOf("slots", Map.of()).forGetter(Loadout::slots), ID.optionalFieldOf("drawn").forGetter(Loadout::drawn)
    ).apply(i, Loadout::new)), Set.of("slots", "drawn"));
}
