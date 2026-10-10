package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class StrandDefenseGameTest extends com.imdomestic.chorus.test.StrandDefenseGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:strand_refresh", maxTicks = 16) @Override
    public void shortReapplicationKeepsLongMailThroughRealTicks(GameTestHelper h) throws Exception { super.shortReapplicationKeepsLongMailThroughRealTicks(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void superBodyTakesUnprotectedDamageThenKeepsItsNewMail(GameTestHelper h) throws Exception { super.superBodyTakesUnprotectedDamageThenKeepsItsNewMail(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualNativeDamageComposesSeverOnTheAttackerAndMailOnTheVictim(GameTestHelper h) throws Exception { super.actualNativeDamageComposesSeverOnTheAttackerAndMailOnTheVictim(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void guardianPrecisionAndMeleeBypassMailButCombatantAttacksDoNot(GameTestHelper h) throws Exception { super.guardianPrecisionAndMeleeBypassMailButCombatantAttacksDoNot(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualAcceptedRecipientSuperClearsAllyMailButRejectedCastDoesNot(GameTestHelper h) throws Exception { super.actualAcceptedRecipientSuperClearsAllyMailButRejectedCastDoesNot(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:strand_expiry", maxTicks = 12) @Override
    public void realTicksRemoveShortMailBeforeTheNextNativeHit(GameTestHelper h) throws Exception { super.realTicksRemoveShortMailBeforeTheNextNativeHit(h); }
}
