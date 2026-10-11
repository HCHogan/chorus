package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class LightweightKnifeGameTest extends com.imdomestic.chorus.test.LightweightKnifeGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void physicalBodyAndConfiguredPrecisionContactsApplyKnownDamageAndRadiant(GameTestHelper h)throws Exception{super.physicalBodyAndConfiguredPrecisionContactsApplyKnownDamageAndRadiant(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void actualGuardianAndUnconfiguredTargetsUseTheirOwnDamageAndRegionData(GameTestHelper h)throws Exception{super.actualGuardianAndUnconfiguredTargetsUseTheirOwnDamageAndRegionData(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void contactGeometrySurvivesTargetMovementAndIntrinsicPrecisionSurvivesDeselection(GameTestHelper h)throws Exception{super.contactGeometrySurvivesTargetMovementAndIntrinsicPrecisionSurvivesDeselection(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void physicalKnifeKillsFeedEquippedOphidiaAndNextKnifeUsesItsCurrentBonus(GameTestHelper h)throws Exception{super.physicalKnifeKillsFeedEquippedOphidiaAndNextKnifeUsesItsCurrentBonus(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:lightweight_ticks",maxTicks=65) @Override
    public void actualTicksAdvanceTheSharedMeterAndEndEmptyFlights(GameTestHelper h)throws Exception{super.actualTicksAdvanceTheSharedMeterAndEndEmptyFlights(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void aRealWallConsumesTheKnifeBeforeItsTargetWithoutRefund(GameTestHelper h)throws Exception{super.aRealWallConsumesTheKnifeBeforeItsTargetWithoutRefund(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownPhysicalDamageKeepsHealthLossAndPaymentWithoutRadiantOrReplay(GameTestHelper h)throws Exception{super.unknownPhysicalDamageKeepsHealthLossAndPaymentWithoutRadiantOrReplay(h);}
}
