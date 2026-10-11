package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EntityAnchorGameTest extends com.imdomestic.chorus.test.EntityAnchorGameTest {
    @GameTest(structure="chorus_gametest:empty",maxTicks=20) @Override
    public void nativeAnchorKeepsOneLifeThroughMovementDeathAndSaveReload(GameTestHelper h) {super.nativeAnchorKeepsOneLifeThroughMovementDeathAndSaveReload(h);}
    @GameTest(structure="chorus_gametest:empty",maxTicks=20) @Override
    public void legacySavesGetALifeOnceAndMalformedPresentValuesAreRejected(GameTestHelper h) {super.legacySavesGetALifeOnceAndMalformedPresentValuesAreRejected(h);}
    @GameTest(structure="chorus_gametest:empty",maxTicks=20) @Override
    public void sameUuidAndNumericIdDoNotAuthorizeDamageToAReplacementLife(GameTestHelper h) {super.sameUuidAndNumericIdDoNotAuthorizeDamageToAReplacementLife(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:entity_life_respawn",maxTicks=40) @Override
    public void actualPlayerRespawnRotatesLifeWhileKeepAllReplacementRetainsIt(GameTestHelper h)throws Exception {super.actualPlayerRespawnRotatesLifeWhileKeepAllReplacementRetainsIt(h);}
    @GameTest(structure="chorus_gametest:empty",maxTicks=20) @Override
    public void deathProtectionDoesNotRotateTheCurrentLife(GameTestHelper h) {super.deathProtectionDoesNotRotateTheCurrentLife(h);}
    @GameTest(structure="chorus_gametest:empty",maxTicks=40) @Override
    public void actualDimensionTransferPreservesLifeButRejectsTheOldWorldReference(GameTestHelper h) {super.actualDimensionTransferPreservesLifeButRejectsTheOldWorldReference(h);}
}
