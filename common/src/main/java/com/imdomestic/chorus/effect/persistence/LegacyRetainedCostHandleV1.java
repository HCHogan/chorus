package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.resource.*;

/** Old handles were local to their issuing session; qualification happens at an explicit cross-domain handoff. */
public record LegacyRetainedCostHandleV1(String id,Resources.CostReceipt receipt,long startedAt,long dueAt) {
    public LegacyRetainedCostHandleV1 { new RetainedCosts.Handle(id,receipt,startedAt,dueAt); }
    public RetainedCosts.Handle current() { return new RetainedCosts.Handle(id,receipt,startedAt,dueAt); }
}
