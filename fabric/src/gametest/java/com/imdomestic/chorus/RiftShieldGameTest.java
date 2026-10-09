package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class RiftShieldGameTest extends com.imdomestic.chorus.test.RiftShieldGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rift_shield_damage", maxTicks = 115) @Override
    public void shieldChargesToCapTakesNativeDamageAndResumesOnlyAfterHealthIsFull(GameTestHelper h) throws Exception { super.shieldChargesToCapTakesNativeDamageAndResumesOnlyAfterHealthIsFull(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rift_shield_overlap", maxTicks = 12) @Override
    public void overlappingPvpFieldsShareGenerationAndLeavingOneDoesNotClearAnother(GameTestHelper h) throws Exception { super.overlappingPvpFieldsShareGenerationAndLeavingOneDoesNotClearAnother(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rift_shield_void", maxTicks = 12) @Override
    public void voidShieldBlocksGenerationWhileOtherLayersAndNativeAbsorptionStayIndependent(GameTestHelper h) throws Exception { super.voidShieldBlocksGenerationWhileOtherLayersAndNativeAbsorptionStayIndependent(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rift_shield_qualification", maxTicks = 10) @Override
    public void riftPresenceCountsForPerkQualificationEvenWhenNoShieldCapacityHasBeenGenerated(GameTestHelper h) throws Exception { super.riftPresenceCountsForPerkQualificationEvenWhenNoShieldCapacityHasBeenGenerated(h); }
}
