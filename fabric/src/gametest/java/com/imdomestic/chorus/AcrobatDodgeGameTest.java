package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AcrobatDodgeGameTest extends com.imdomestic.chorus.test.AcrobatDodgeGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:acrobat_input", maxTicks=18) @Override
    public void actualInputSetsHorizontalAndVerticalVelocityBeforeDelayedSameLifeBenefits(GameTestHelper h) { super.actualInputSetsHorizontalAndVerticalVelocityBeforeDelayedSameLifeBenefits(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:acrobat_radius", maxTicks=18) @Override
    public void completionUsesCurrentCasterPositionAndRecipientSolaceWithRealEnemyDamage(GameTestHelper h) { super.completionUsesCurrentCasterPositionAndRecipientSolaceWithRealEnemyDamage(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:acrobat_neutral", maxTicks=18) @Override
    public void airborneNeutralUsesTheExplicitCalibratedBackwardFallback(GameTestHelper h) { super.airborneNeutralUsesTheExplicitCalibratedBackwardFallback(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:acrobat_ascension", maxTicks=18) @Override
    public void actualAscensionPaysAcrobatAccountAndRetainsOnlyConfiguredSharedBenefits(GameTestHelper h) { super.actualAscensionPaysAcrobatAccountAndRetainsOnlyConfiguredSharedBenefits(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:acrobat_dead", maxTicks=18) @Override
    public void actualDeathBeforeCompletionPreventsRadiantAndBlast(GameTestHelper h) { super.actualDeathBeforeCompletionPreventsRadiantAndBlast(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:acrobat_unknown", maxTicks=18) @Override
    public void unknownCompletionAuthorizationRetainsPaymentAndNativeVelocityWithoutReplay(GameTestHelper h) { super.unknownCompletionAuthorizationRetainsPaymentAndNativeVelocityWithoutReplay(h); }
}
