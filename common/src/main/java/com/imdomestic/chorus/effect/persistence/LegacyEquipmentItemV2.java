package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.equipment.EquipmentSchema;
import java.util.*;

/** Exact budget-only shape; no access requirements were declared by these catalogues. */
public record LegacyEquipmentItemV2(String id, Set<String> tags, Map<String, EquipmentSchema.Effect> effects,
        Map<String, EquipmentSchema.Socket> sockets, Map<String, EquipmentSchema.Parameter> parameters, Map<String, EquipmentSchema.Budget> budgets) {
    public LegacyEquipmentItemV2 { new EquipmentSchema.Item(id, tags, effects, sockets, parameters, budgets); }
    public EquipmentSchema.Item current() { return new EquipmentSchema.Item(id, tags, effects, sockets, parameters, budgets); }
}
