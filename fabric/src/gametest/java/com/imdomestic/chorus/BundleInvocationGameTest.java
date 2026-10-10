package com.imdomestic.chorus;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class BundleInvocationGameTest extends com.imdomestic.chorus.test.BundleInvocationGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:invocation_flight",maxTicks=60) @Override
    public void realProjectileAfterUnequipInvokesSlowWithCapturedCreditAndCurrentDurance(GameTestHelper h){super.realProjectileAfterUnequipInvokesSlowWithCapturedCreditAndCurrentDurance(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:invocation_freeze") @Override
    public void invokedSlowReusesInheritedFreezeAuthorizationAndFinalApplierCredit(GameTestHelper h){super.invokedSlowReusesInheritedFreezeAuthorizationAndFinalApplierCredit(h);}
}
