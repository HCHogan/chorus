package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.target.*;

/** Original local handle shape; only an explicit handoff can establish its issuing domain. */
public record LegacyEntityAttachmentHandleV1(String id, long startedAt, long expiresAt, EntityAnchor.Observation initial,
        WorldPosition contact, EntityAttachments.Policy policy) {
    public LegacyEntityAttachmentHandleV1 { new EntityAttachments.Handle(id, startedAt, expiresAt, initial, contact, policy); }
    public EntityAttachments.Handle current() { return new EntityAttachments.Handle(id, startedAt, expiresAt, initial, contact, policy); }
}
