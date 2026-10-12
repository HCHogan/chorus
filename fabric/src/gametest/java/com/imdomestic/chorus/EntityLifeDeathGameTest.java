package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EntityLifeDeathGameTest {
    @GameTest
    public void managedDamageKeepsTheReceiptDeathIdentityAndParentFrameOrdering(GameTestHelper h) throws Exception { new com.imdomestic.chorus.test.EntityLifeDeathGameTest().managedDamageKeepsTheReceiptDeathIdentityAndParentFrameOrdering(h); }
    @GameTest
    public void nativeDeathKeepsTheExactBoundaryBeforeLaterMovementAndRemoval(GameTestHelper h) throws Exception { new com.imdomestic.chorus.test.EntityLifeDeathGameTest().nativeDeathKeepsTheExactBoundaryBeforeLaterMovementAndRemoval(h); }
    @GameTest
    public void actualServerPlayerDeathCapturesTheSameOldLife(GameTestHelper h) throws Exception { new com.imdomestic.chorus.test.EntityLifeDeathGameTest().actualServerPlayerDeathCapturesTheSameOldLife(h); }
    @GameTest
    public void nativeProtectionDoesNotCreateADeathLifeObservation(GameTestHelper h) throws Exception { new com.imdomestic.chorus.test.EntityLifeDeathGameTest().nativeProtectionDoesNotCreateADeathLifeObservation(h); }
    @GameTest
    public void unknownOutcomeRetainsConfirmedDeathButDoesNotRunRewards(GameTestHelper h) throws Exception { new com.imdomestic.chorus.test.EntityLifeDeathGameTest().unknownOutcomeRetainsConfirmedDeathButDoesNotRunRewards(h); }
    @GameTest(environment = "chorus_gametest:entity_life_death_delayed", maxTicks = 25)
    public void detachedDeathReadRetainsTheOldPointAfterSourceAndCorpseDisappear(GameTestHelper h) throws Exception { new com.imdomestic.chorus.test.EntityLifeDeathGameTest().detachedDeathReadRetainsTheOldPointAfterSourceAndCorpseDisappear(h); }
}
