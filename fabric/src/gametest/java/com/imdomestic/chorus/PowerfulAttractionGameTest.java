package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class PowerfulAttractionGameTest extends com.imdomestic.chorus.test.PowerfulAttractionGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void independentPhysicalClassItemCopiesCollectAtBothExactRangeBoundaries(GameTestHelper h) throws Exception { super.independentPhysicalClassItemCopiesCollectAtBothExactRangeBoundaries(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void actualMarksmanDodgeCommandConsumesItsChargeAndCollectsWithoutMovement(GameTestHelper h) throws Exception { super.actualMarksmanDodgeCommandConsumesItsChargeAndCollectsWithoutMovement(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void resolvedReplacementMetadataControlsEligibilityAndUnrelatedPickupKindsRemain(GameTestHelper h) throws Exception { super.resolvedReplacementMetadataControlsEligibilityAndUnrelatedPickupKindsRemain(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void socketConfiguredUtilityKickstartSpendsThenOrbsRefillTheLivePool(GameTestHelper h) throws Exception { super.socketConfiguredUtilityKickstartSpendsThenOrbsRefillTheLivePool(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void starvationAllowsFullSuperOrbsAndOnlyTheCollectorReceivesDevour(GameTestHelper h) throws Exception { super.starvationAllowsFullSuperOrbsAndOnlyTheCollectorReceivesDevour(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownNestedHealingKeepsPaidDodgeAndStopsBeforeTheSecondOrb(GameTestHelper h) throws Exception { super.unknownNestedHealingKeepsPaidDodgeAndStopsBeforeTheSecondOrb(h); }
}
