package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class HealthLayerGameTest extends com.imdomestic.chorus.test.HealthLayerGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void managedAndNativeHitsScaleOnlyThePostArmorPostAbsorptionHealthBudget(GameTestHelper h) { super.managedAndNativeHitsScaleOnlyThePostArmorPostAbsorptionHealthBudget(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void shieldAndAbsorptionZeroBudgetsDoNotActivateFlatHealthStages(GameTestHelper h) { super.shieldAndAbsorptionZeroBudgetsDoNotActivateFlatHealthStages(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void zeroHealthMultiplierBlocksOnlyHealthAndPreservesEarlierLosses(GameTestHelper h) { super.zeroHealthMultiplierBlocksOnlyHealthAndPreservesEarlierLosses(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void cancellationImmunityAndCooldownNeverReachTheHealthProfile(GameTestHelper h) { super.cancellationImmunityAndCooldownNeverReachTheHealthProfile(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void transformedDamageKeepsNativeDeathProtectionAndNonlethalFloors(GameTestHelper h) { super.transformedDamageKeepsNativeDeathProtectionAndNonlethalFloors(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nestedNativeDamageUsesItsOwnHealthBudgetAndTrace(GameTestHelper h) { super.nestedNativeDamageUsesItsOwnHealthBudgetAndTrace(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownHealthQueryKeepsPriorShieldAndAbsorptionWritesWithoutApplyingFallbackDamage(GameTestHelper h) { super.unknownHealthQueryKeepsPriorShieldAndAbsorptionWritesWithoutApplyingFallbackDamage(h); }
}
