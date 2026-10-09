package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class VolatileGameTest extends com.imdomestic.chorus.test.VolatileGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeThresholdDetonatesSelfAndNearbyTargetsWithModeSpecificDamageAndCooldown(GameTestHelper h) throws Exception { super.nativeThresholdDetonatesSelfAndNearbyTargetsWithModeSpecificDamageAndCooldown(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void lethalApplyingHitTriggersOneQualifiedExplosionWithoutApplyingToADeadEntity(GameTestHelper h) throws Exception { super.lethalApplyingHitTriggersOneQualifiedExplosionWithoutApplyingToADeadEntity(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void lethalHitOnAnExistingStatusDoesNotDetonateTwiceOrChangeTheApplicationOrigin(GameTestHelper h) throws Exception { super.lethalHitOnAnExistingStatusDoesNotDetonateTwiceOrChangeTheApplicationOrigin(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void volatileExplosionsCanDetonateOtherVolatileTargetsInTheSameCausalRoot(GameTestHelper h) throws Exception { super.volatileExplosionsCanDetonateOtherVolatileTargetsInTheSameCausalRoot(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void readOnlyDeadQualificationStillHonorsStatusPolicyAndMissingEntities(GameTestHelper h) throws Exception { super.readOnlyDeadQualificationStillHonorsStatusPolicyAndMissingEntities(h); }
}
