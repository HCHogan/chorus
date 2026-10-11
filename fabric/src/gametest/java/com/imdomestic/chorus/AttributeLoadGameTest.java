package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AttributeLoadGameTest extends com.imdomestic.chorus.test.AttributeLoadGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unloadedMobRegainsMaximumBeforeSavedHealthIsClampedWithoutSerializingTransientModifiers(GameTestHelper h) throws Exception {
        super.unloadedMobRegainsMaximumBeforeSavedHealthIsClampedWithoutSerializingTransientModifiers(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void serverPlayerNbtKeepsBonusHealthBeforeLoginIndexesThePlayer(GameTestHelper h) throws Exception {
        super.serverPlayerNbtKeepsBonusHealthBeforeLoginIndexesThePlayer(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void repeatedEntityDataLoadsUseSavedLivenessAndDoNotStackOrHeal(GameTestHelper h) throws Exception {
        super.repeatedEntityDataLoadsUseSavedLivenessAndDoNotStackOrHeal(h);
    }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:attribute_load_expiry", maxTicks = 14) @Override
    public void entityLoadedAfterBuffExpiryDoesNotReacquireItsSavedCapacity(GameTestHelper h) throws Exception {
        super.entityLoadedAfterBuffExpiryDoesNotReacquireItsSavedCapacity(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void loadingOneEntityDoesNotRewriteAnotherEntityOrActivateAnUnregisteredHolder(GameTestHelper h) throws Exception {
        super.loadingOneEntityDoesNotRewriteAnotherEntityOrActivateAnUnregisteredHolder(h);
    }
}
