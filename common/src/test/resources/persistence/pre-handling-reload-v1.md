# Pre-handling format 1 checkpoint

Generated on 2026-10-12 from the pre-handling WeaponDefinition and EffectState shapes at baseline 0543f21, before either model was changed. RecordTreeCodec had new unused migration support; no migrations were registered when this file was written.

The WeaponReloadTest fixture equips two Kill Clip rifles, records a weapon kill, starts a reload, and advances to 50,000 microseconds. The accepted reload ends at 200,000 microseconds. The checkpoint retains the original catalogue, buffs, equipment, ammunition, timer and event/frame sequences.

The temporary exporter used the generic timeline checkpoint paired with its exact data catalogue; the original harness does not pin a catalogue in EffectSession. Do not regenerate this historical wire fixture using new record shapes.

SHA-256: `018f7738ada5086334c530ca2a7adebba48db14eaf53782e754523f23e44ff03`
