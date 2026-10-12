package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.equipment.EquipmentSchema;
import java.util.*;

/** Exact pre-budget item shape; old catalogues declare no allocation constraints. */
public record LegacyEquipmentItemV1(String id, Set<String> tags, Map<String, EquipmentSchema.Effect> effects,
        Map<String, EquipmentSchema.Socket> sockets, Map<String, EquipmentSchema.Parameter> parameters) {
    public LegacyEquipmentItemV1 { new EquipmentSchema.Item(id, tags, effects, sockets, parameters); }
    public EquipmentSchema.Item current() { return new EquipmentSchema.Item(id, tags, effects, sockets, parameters); }
}
