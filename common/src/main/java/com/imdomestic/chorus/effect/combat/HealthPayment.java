package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Explicit nonlethal health expenditure, independent of damage and healing. */
public final class HealthPayment {
    private HealthPayment() {}
    public enum Mode { EXACT, UP_TO }
    public enum Outcome { PAID, INSUFFICIENT, DEAD, MISSING }
    public record Command(String target, BuffInstance.Origin source, double amount, double minimum, Mode mode, Set<String> tags) implements RuleEngine.WorldCommand {
        public Command {
            if(target==null||target.isBlank())throw new IllegalArgumentException("Missing health payment target");
            Objects.requireNonNull(source);Objects.requireNonNull(mode);tags=Set.copyOf(tags);
            Numbers.nonnegative(amount,"health payment");Numbers.finite(minimum,"minimum health");
            if(minimum<=0)throw new IllegalArgumentException("Health payment requires a positive nonlethal floor");
        }
    }
    public record Balance(double before,double after) {
        public Balance { Numbers.nonnegative(before,"health before");Numbers.nonnegative(after,"health after");if(after>before)throw new IllegalArgumentException("Payment cannot heal"); }
        public double effective(){return before-after;}
    }
    public record Receipt(String paymentId,Command command,Outcome outcome,Optional<Balance> balance) implements RuleEngine.ActionResult {
        public Receipt {
            if(paymentId==null||paymentId.isBlank())throw new IllegalArgumentException("Missing health payment identity");
            Objects.requireNonNull(command);Objects.requireNonNull(outcome);Objects.requireNonNull(balance);
            if(balance.isPresent()!=(outcome==Outcome.PAID||outcome==Outcome.INSUFFICIENT))throw new IllegalArgumentException("Health payment observation differs from outcome");
            balance.ifPresent(b->{
                if(b.before()<=0||b.after()<=0||b.after()<Math.min(b.before(),command.minimum())||b.effective()>command.amount())throw new IllegalArgumentException("Invalid health payment balance");
                double available=Math.max(0,b.before()-command.minimum());
                if(outcome==Outcome.INSUFFICIENT&&(command.mode()!=Mode.EXACT||command.amount()<=available||b.effective()!=0))throw new IllegalArgumentException("Invalid insufficient health payment");
                if(outcome==Outcome.PAID&&command.mode()==Mode.EXACT&&command.amount()>available)throw new IllegalArgumentException("Unaffordable exact health payment");
            });
        }
        public boolean paid(){return outcome==Outcome.PAID;}
        public double effective(){return balance.map(Balance::effective).orElse(0.0);}
        public static Receipt unavailable(String id,Command c,Outcome outcome){return new Receipt(id,c,outcome,Optional.empty());}
    }
    /** Affordability and a nonlethal target; the native adapter reports its actual quantized write. */
    public static Receipt plan(String id,Command command,double health){
        Numbers.nonnegative(health,"current health");if(health==0)return Receipt.unavailable(id,command,Outcome.DEAD);
        double available=Math.max(0,health-command.minimum());
        if(command.mode()==Mode.EXACT&&command.amount()>available)return new Receipt(id,command,Outcome.INSUFFICIENT,Optional.of(new Balance(health,health)));
        double budget=Math.min(command.amount(),available),after=Math.max(Math.min(health,command.minimum()),health-budget);
        if(health-after>budget)after=Math.nextUp(after);
        return new Receipt(id,command,Outcome.PAID,Optional.of(new Balance(health,after)));
    }
    public static List<RuleEngine.Signal> facts(Receipt receipt){
        if(!receipt.paid()||receipt.effective()==0)return List.of();var c=receipt.command();var b=receipt.balance().orElseThrow();
        var event=new EffectEvent(c.source().owner(),c.target(),c.source(),c.tags(),Map.of("requested",new Measure(c.amount(),Unit.DAMAGE),"effective",new Measure(b.effective(),Unit.DAMAGE),"health_before",new Measure(b.before(),Unit.DAMAGE),"health_after",new Measure(b.after(),Unit.DAMAGE)),Map.of("paid",true),Map.of("payment_id",receipt.paymentId()));
        return List.of(new RuleEngine.Signal("chorus:health_spent",event));
    }
}
