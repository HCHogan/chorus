package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DomineeringTest {
    static final String D="chorus_d2:";
    static EffectSource fragment(String id,String actor) {return new EffectSource(id,D+"echo_of_domineering",actor,new BuffInstance.Origin(actor,id,"b",""),Set.of(),Map.of("receipt_seconds",new Measure(12,Unit.SECOND)));}
    static EffectSource application(String actor) {return new EffectSource("suppress/"+actor,D+"suppression_application",actor,new BuffInstance.Origin(actor,"suppression","b","ability"),Set.of(),Map.of("combatant_duration",new Measure(10,Unit.SECOND),"guardian_duration",new Measure(5,Unit.SECOND)));}
    static class Harness extends StarvationTest.Harness {
        StatusResult.Decision decision=StatusResult.Decision.ALLOWED;boolean unknownStatus,omitBuffs;
        StatusResult.Check lastCheck;RuleEngine.OperationId lastOperation;int beforeStacks;
        Harness() throws Exception {super("suppression","echo_of_domineering","directional_movement_attributes","domineering_inputs");equip(fragment("fragment","player"));equipment(WeaponReloadTest.pair("primary"));}
        void equipment(Loadout loadout) {session.start(now(),new EquipmentChange("player",state().equipment().getOrDefault("player",Loadout.EMPTY),loadout).signal());healthy();}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            if(request.command() instanceof StatusResult.Check check) {
                lastCheck=check;lastOperation=request.id();beforeStacks=status("suppression",check.target()).map(BuffInstance::count).orElse(0);
                if(unknownStatus)throw new IllegalStateException("unknown suppression authorization");
                return new StatusResult.Checked(check,decision);
            }
            if(request.command() instanceof DamageCommand damage) {
                var buffs=omitBuffs?new BuffObservation(now(),Map.of()):BuffObservation.capture(state().buffs(),damage.source().owner(),damage.target());
                double before=health.get(damage.target()),loss=Math.min(before,damage.amount());health.put(damage.target(),before-loss);
                var entities=new EntityObservation(now(),Map.of(damage.target(),view(damage.target())),Map.of(new PositionQuery(damage.target()),Optional.of(StarvationTest.POINT)));
                lastCommand=damage;lastReceipt=new DamageReceipt(request.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,loss,before>0&&loss==before?Optional.of("death/"+request.id()):Optional.empty(),false).withObservedBuffs(buffs).withObservedEntities(entities);
                afterDamage.run();return lastReceipt;
            }
            return super.world(request);
        }
        void apply(String actor) {var s=application(actor);equip(s);event(s,"enemy","apply_suppression");}
        Optional<BuffInstance> status(String name,String actor) {return state().buffs().instances().values().stream().filter(b->b.key().holder().equals(actor)&&b.definition().id().equals(D+name)).findFirst();}
        Optional<BuffInstance> status(String name) {return status(name,"player");}
        int magazine(String weapon) {return state().ammunition().get(weapon).magazine();}
        void spend(String weapon,int rounds) {session.start(now(),new RuleEngine.Signal("test:spend",new EffectEvent("player","player",new BuffInstance.Origin("player","input",weapon,""),Set.of(),Map.of("rounds",new Measure(rounds,Unit.ROUND)))));healthy();}
        double stat(String name,double base) {return p.attribute(state(),"player",D+name+"_stat",new Measure(base,Unit.STAT_POINT),new com.imdomestic.chorus.effect.data.NumericQuery.Path(Set.of(),Set.of())).output().value();}
        double forward() {return p.nativeAttributes(state(),"player").stream().filter(c->c.binding().attribute().equals("chorus:forward_ground_speed")).findFirst().orElseThrow().amount();}
        RuleEngine.Signal statusFact() {var b=status("suppression","enemy").orElseThrow();return StatusFacts.applied(lastOperation,lastCheck,new StatusResult(Optional.empty(),true,beforeStacks,b.count(),Optional.of(new EffectState.Lifetime(b.key(),b.generation())),StatusResult.Decision.ALLOWED));}
    }
    @Test void actualSuppressionRefillsCurrentWeaponAndGrantsBothBonusesWithoutReloadCredit() throws Exception {
        var h=new Harness();assertEquals(60,h.stat("grenade",50));h.apply("player");
        assertEquals(5,h.magazine("a"));assertEquals(1,h.magazine("b"));assertEquals(8,h.state().ammunition().get("a").reserve().orElseThrow().rounds());
        assertEquals(80,h.stat("weapons",50));assertEquals(.04,h.forward(),1e-12);assertEquals(10_000_000,h.status("domineering_active").orElseThrow().deadline());
        assertTrue(h.heals.isEmpty());assertTrue(h.status("kill_clip").isEmpty());
    }
    @Test void fourSecondCooldownDoesNotRefreshTenSecondBenefitsUntilANewQualifiedApplication() throws Exception {
        var h=new Harness();h.apply("player");h.spend("a",5);h.until(3_999_999);h.apply("player");
        assertEquals(0,h.magazine("a"));assertEquals(10_000_000,h.status("domineering_active").orElseThrow().deadline());
        h.until(4_000_000);h.equipment(WeaponReloadTest.pair("secondary"));h.apply("player");assertEquals(5,h.magazine("b"));assertEquals(0,h.magazine("a"));assertEquals(14_000_000,h.status("domineering_active").orElseThrow().deadline());
        h.until(14_000_000);assertEquals(50,h.stat("weapons",50));assertEquals(0,h.forward());assertEquals(60,h.stat("grenade",50));
    }
    @Test void applicantCreditIsIndependentOfRetainedStatusOriginAndDuplicateFragmentsDoNotStack() throws Exception {
        var h=new Harness();h.apply("giver");var initial=h.status("suppression","enemy").orElseThrow();assertTrue(h.status("domineering_active").isEmpty());
        h.equip(fragment("duplicate","player"));assertEquals(60,h.stat("grenade",50));h.apply("player");assertEquals(initial.origin(),h.status("suppression","enemy").orElseThrow().origin());assertEquals(5,h.magazine("a"));assertEquals(80,h.stat("weapons",50));assertEquals(.04,h.forward(),1e-12);
    }
    @Test void rejectedDeadMissingAndPureGrantDoNotPretendToBeSuccessfulSuppression() throws Exception {
        for(String reason:List.of("denied","dead","missing","pure")) {
            var h=new Harness();if(reason.equals("denied"))h.decision=StatusResult.Decision.DENIED;
            if(reason.equals("dead"))h.health.put("enemy",0.0);if(reason.equals("missing"))h.kinds.remove("enemy");
            if(reason.equals("pure")){h.bind("pure","test:domineering_inputs","player");h.session.start(h.now(),new RuleEngine.Signal("test:pure_suppression",new EffectEvent("player","enemy",new BuffInstance.Origin("player","pure","",""),Set.of(),Map.of())));}else h.apply("player");
            assertEquals(1,h.magazine("a"),reason);assertTrue(h.status("domineering_active").isEmpty(),reason);assertTrue(h.status("domineering_cooldown").isEmpty(),reason);
        }
    }
    @Test void targetClassificationControlsSharedSuppressionDurationIndependentlyOfWorldMode() throws Exception {
        for(boolean guardian:List.of(false,true)) {var h=new Harness();h.entity("enemy",100,guardian,Set.of());h.apply("player");assertEquals(guardian?5_000_000:10_000_000,h.status("suppression","enemy").orElseThrow().deadline());assertEquals(10_000_000,h.status("domineering_active").orElseThrow().deadline());}
    }
    @Test void emptyHandsStillReceiveBonusesAndCooldownButStowedAmmoIsUntouched() throws Exception {
        var h=new Harness();h.equipment(new Loadout(h.state().equipment().get("player").slots(),Optional.empty()));h.apply("player");assertEquals(1,h.magazine("a"));assertEquals(1,h.magazine("b"));assertEquals(200,h.stat("weapons",190));assertTrue(h.status("domineering_cooldown").isPresent());
        h.remove("fragment");assertEquals(50,h.stat("grenade",50));assertEquals(80,h.stat("weapons",50),"already accepted timed benefit retains lifetime");h.until(10_000_000);assertEquals(50,h.stat("weapons",50));
    }
    @Test void suppressedKillUsesHistoricalStateAfterDeathCleanupAndCollectsSharedBreach() throws Exception {
        var h=new Harness();h.apply("giver");h.afterDamage=()->h.kinds.remove("enemy");h.kill();assertTrue(h.status("suppression","enemy").isEmpty());assertEquals(1,h.spawns.size());assertEquals(D+"void_breach",h.spawns.getFirst().kind());assertEquals(Optional.of(StarvationTest.POINT),h.spawns.getFirst().position());assertTrue(h.status("domineering_cooldown").isEmpty(),"kill branch is independent of application branch");
        h.finish(0,true);assertEquals(.025,h.account("class_a"),1e-12);assertEquals(170,h.health.get("player"));assertTrue(h.active());
    }
    @Test void nonSuppressedExpiredUnknownAndOtherPlayersKillsDoNotGenerateBreach() throws Exception {
        for(String reason:List.of("none","expired","unknown","other")) {var h=new Harness();if(!reason.equals("none"))h.apply("giver");if(reason.equals("expired"))h.until(10_000_000);h.omitBuffs=reason.equals("unknown");h.damage(reason.equals("other")?"giver":"player","enemy",1000);assertTrue(h.spawns.isEmpty(),reason);}
    }
    @Test void receiptsSurviveCheckpointRebindingAndCooldownAndDoNotTurnOldFactsIntoNewBenefits() throws Exception {
        var h=new Harness();h.apply("player");var first=h.statusFact();h.spend("a",5);h.until(1_000_000);h.apply("player");var during=h.statusFact();h.remove("fragment");h.restore();h.equip(fragment("again","player"));h.until(4_000_000);h.replay(List.of(first,during));assertEquals(0,h.magazine("a"));assertEquals(10_000_000,h.status("domineering_active").orElseThrow().deadline());h.apply("player");assertEquals(5,h.magazine("a"));
        h.kill();var facts=h.facts();h.until(9_000_000);h.replay(facts);assertEquals(1,h.spawns.size());h.until(16_000_000);assertTrue(h.status("domineering_receipts").isEmpty());
    }
    @Test void unknownAuthorizationAndUnknownBreachCreationDoNotReplayWorldWork() throws Exception {
        var h=new Harness();h.unknownStatus=true;assertThrows(IllegalStateException.class,()->h.apply("player"));assertTrue(h.status("domineering_active").isEmpty());assertEquals(1,h.magazine("a"));
        var other=new Harness();other.apply("player");other.failSpawn=true;assertThrows(IllegalStateException.class,other::kill);assertEquals(1,other.spawns.size());assertEquals(5,other.magazine("a"));assertThrows(IllegalStateException.class,()->other.until(5_000_000));assertEquals(1,other.spawns.size());
    }
}
