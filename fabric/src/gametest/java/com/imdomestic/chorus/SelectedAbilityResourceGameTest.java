package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SelectedAbilityResourceGameTest extends com.imdomestic.chorus.test.SelectedAbilityResourceGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:selected_resource_rates", maxTicks=80) @Override
    public void serverTicksApplyTheTemporaryBonusOnlyToTheCurrentClassAccountAcrossSwitchAndClear(GameTestHelper h) throws Exception { super.serverTicksApplyTheTemporaryBonusOnlyToTheCurrentClassAccountAcrossSwitchAndClear(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:selected_resource_recharge", maxTicks=35) @Override
    public void explicitRechargeResourceReceivesTheBonusIndependentlyOfItsPaymentAccount(GameTestHelper h) throws Exception { super.explicitRechargeResourceReceivesTheBonusIndependentlyOfItsPaymentAccount(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:selected_resource_delay", maxTicks=16) @Override
    public void detachedConditionalHealingReadsTheNewSelectedAccountOnRealTicks(GameTestHelper h) throws Exception { super.detachedConditionalHealingReadsTheNewSelectedAccountOnRealTicks(h); }
}
