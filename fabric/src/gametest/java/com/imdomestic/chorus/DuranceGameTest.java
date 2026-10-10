package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DuranceGameTest extends com.imdomestic.chorus.test.DuranceGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:durance_sources") @Override
    public void actualGuardianAndCombatantSlowUseDifferentSourceExtensionsAndOnlyApplierEquipment(GameTestHelper h){super.actualGuardianAndCombatantSlowUseDifferentSourceExtensionsAndOnlyApplierEquipment(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:durance_expiry",maxTicks=60) @Override
    public void removingFragmentKeepsCommittedDeadlineAndNativeSpeedRestoresAtExtendedExpiry(GameTestHelper h){super.removingFragmentKeepsCommittedDeadlineAndNativeSpeedRestoresAtExtendedExpiry(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:durance_conversion") @Override
    public void extendedSlowStillNeedsNativeAuthorizationAndItsFreezeConversionRetainsIndependentTiming(GameTestHelper h){super.extendedSlowStillNeedsNativeAuthorizationAndItsFreezeConversionRetainsIndependentTiming(h);}
}
