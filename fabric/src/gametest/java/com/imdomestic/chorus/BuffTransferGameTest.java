package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class BuffTransferGameTest extends com.imdomestic.chorus.test.BuffTransferGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void relocatedBuffHealsTheActualTransferredPlayerOnceAfterJointCoreRestore(GameTestHelper h){super.relocatedBuffHealsTheActualTransferredPlayerOnceAfterJointCoreRestore(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void unknownRelocatedHealingKeepsPhysicalHealthRefundAndIssuerClaimWithoutReplay(GameTestHelper h){super.unknownRelocatedHealingKeepsPhysicalHealthRefundAndIssuerClaimWithoutReplay(h);}
}
