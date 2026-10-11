# Pre-aim format 1 checkpoint

Generated on 2026-10-12 at baseline 633d616, before changing WeaponDefinition, EffectState or ActionGate.Kind for aiming.

The real WeaponHandlingTest harness equips two Kill Clip rifles, binds the synthetic half-duration modifier, requests the secondary slot and advances to 50,000 microseconds. Its accepted STOW ends at 100,000 microseconds; READY should then end at 250,000. The original catalogue, modifier, equipment, ammunition, timer and event/frame counters are preserved. A dormant `test:aim_migration_gate` bundle with a WEAPON_FIRE restriction also preserves the old ActionGate.Kind wire representation; it was added to the test catalogue before export, without binding it.

Written by EffectCheckpointCodec.encode(session.checkpoint()) using the old running implementation. The temporary exporter was removed after the file was copied byte-for-byte. Do not regenerate with the new record or enum shapes.

SHA-256: `6451048f25132aac9bb79718cea76e22ee7b98c5106122fbd3ae3c8b2eb778a4`
