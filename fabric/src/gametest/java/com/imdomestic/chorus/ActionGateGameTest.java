package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ActionGateGameTest extends com.imdomestic.chorus.test.ActionGateGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:action_gate_ability") @Override
    public void ordinaryAbilityCommandChecksTheReplacementAndCannotBorrowAnotherPlayersRestriction(GameTestHelper h)throws Exception{super.ordinaryAbilityCommandChecksTheReplacementAndCannotBorrowAnotherPlayersRestriction(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:action_gate_expiry",maxTicks=14) @Override
    public void aDebuffRestrictsItsRecipientUntilExpiryWithoutConsumingAbilityEnergy(GameTestHelper h)throws Exception{super.aDebuffRestrictsItsRecipientUntilExpiryWithoutConsumingAbilityEnergy(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:action_gate_reload",maxTicks=14) @Override
    public void deniedWeaponInputsDoNotSpendAmmoOrCancelThePlanUntilItsCompletionCheck(GameTestHelper h)throws Exception{super.deniedWeaponInputsDoNotSpendAmmoOrCancelThePlanUntilItsCompletionCheck(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:action_gate_insert",maxTicks=20) @Override
    public void anInsertionReactionCancelsTheNextStepWithoutUndoingTheCommittedRound(GameTestHelper h)throws Exception{super.anInsertionReactionCancelsTheNextStepWithoutUndoingTheCommittedRound(h);}
}
