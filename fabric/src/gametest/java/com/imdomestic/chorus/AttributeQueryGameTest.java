package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AttributeQueryGameTest extends com.imdomestic.chorus.test.AttributeQueryGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:attribute_later",maxTicks=30) @Override
    public void actualDelayedHealingSeparatesCapturedAndCurrentAttributesAfterSourceRemoval(GameTestHelper h)throws Exception {super.actualDelayedHealingSeparatesCapturedAndCurrentAttributesAfterSourceRemoval(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void nativeHitsUseFrozenOwnerAndCurrentVictimAttributeContributions(GameTestHelper h)throws Exception {super.nativeHitsUseFrozenOwnerAndCurrentVictimAttributeContributions(h);}
}
