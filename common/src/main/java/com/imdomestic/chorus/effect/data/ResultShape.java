package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.buff.Buffs;
import com.imdomestic.chorus.effect.resource.ResourceResult;
import com.imdomestic.chorus.effect.resource.Resources;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.combat.StatusResult;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.function.Predicate;

/** Named, typed projections of an action result. Custom actions may provide their own shape. */
public record ResultShape(Map<String, Field> fields, Map<String, Predicate<RuleEngine.ActionResult>> flags, boolean carriesCost, Reference reference) {
    public enum Reference { NONE, TARGET, TARGETS, TARGET_IDENTITIES, TARGET_DIFFERENCE, DAMAGE_SNAPSHOT, POSITION, DIRECTION, PROJECTILE_IMPACT, PICKUP_CONTACT, SHOT, SHOT_IMPACT, DAMAGE_BATCH, DAMAGE_GROUP, RETAINED_COST, DAMAGE_TALLY, DAMAGE_RECEIPT, ENTITY_OBSERVATION }
    public record Field(Unit unit, ToDoubleFunction<RuleEngine.ActionResult> read) {}
    public ResultShape { fields = Map.copyOf(fields); flags = Map.copyOf(flags); java.util.Objects.requireNonNull(reference); }
    public ResultShape(Map<String, Field> fields, Map<String, Predicate<RuleEngine.ActionResult>> flags, boolean carriesCost) { this(fields, flags, carriesCost, Reference.NONE); }
    public ResultShape(Map<String, Field> fields, Map<String, Predicate<RuleEngine.ActionResult>> flags) { this(fields, flags, false); }
    public ResultShape(Map<String, Field> fields) { this(fields, Map.of()); }
    public void requireTarget() { if (reference != Reference.TARGET) throw new IllegalArgumentException("Result is not an entity target"); }
    public void requireTargets() { if (reference != Reference.TARGETS && reference != Reference.TARGET_IDENTITIES && reference != Reference.PROJECTILE_IMPACT && reference != Reference.SHOT_IMPACT && reference != Reference.PICKUP_CONTACT) throw new IllegalArgumentException("Result is not a target collection"); }
    public void requireTargetDifference() { if (reference != Reference.TARGET_DIFFERENCE) throw new IllegalArgumentException("Result is not a target membership difference"); }
    public ResultShape targetElement() { requireTargets(); return reference == Reference.TARGETS ? TARGET : TARGET_IDENTITY; }
    public void requireSnapshot() { if (reference != Reference.DAMAGE_SNAPSHOT) throw new IllegalArgumentException("Result is not a damage snapshot"); }
    public void requireDirection() { if (reference != Reference.DIRECTION) throw new IllegalArgumentException("Result is not a direction capture"); }
    public java.util.Optional<com.imdomestic.chorus.effect.target.WorldDirection> direction(RuleEngine.ActionResult result) {
        requireDirection(); return ((com.imdomestic.chorus.effect.target.DirectionResult) result).direction();
    }
    private static com.imdomestic.chorus.effect.projectile.ProjectileFlight.Impact impact(RuleEngine.ActionResult result) { return (com.imdomestic.chorus.effect.projectile.ProjectileFlight.Impact) result; }
    private static com.imdomestic.chorus.effect.object.WorldPickup.Contact pickup(RuleEngine.ActionResult result) { return (com.imdomestic.chorus.effect.object.WorldPickup.Contact) result; }
    public static final ResultShape PICKUP_SPAWN;
    static {
        var flags = new java.util.HashMap<String, Predicate<RuleEngine.ActionResult>>();
        for (var outcome : com.imdomestic.chorus.effect.object.WorldPickup.Outcome.values())
            flags.put(outcome.name().toLowerCase(java.util.Locale.ROOT), r -> ((com.imdomestic.chorus.effect.object.WorldPickup.Receipt) r).outcome() == outcome);
        PICKUP_SPAWN = new ResultShape(Map.of("count", new Field(Unit.COUNT,
                r -> ((com.imdomestic.chorus.effect.object.WorldPickup.Receipt) r).entity().isPresent() ? 1 : 0)), flags);
    }
    public static final ResultShape PICKUP_CONTACT = new ResultShape(Map.of(
            "count", new Field(Unit.COUNT, r -> pickup(r).targets().size()), "age", new Field(Unit.SECOND, r -> pickup(r).ageMicros() / 1_000_000.0)),
            Map.of("collected", r -> pickup(r).end() == com.imdomestic.chorus.effect.object.WorldPickup.End.COLLECTED,
                    "expired", r -> pickup(r).end() == com.imdomestic.chorus.effect.object.WorldPickup.End.EXPIRED), false, Reference.PICKUP_CONTACT);
    public static final ResultShape PROJECTILE_IMPACT = new ResultShape(Map.of(
            "count", new Field(Unit.COUNT, r -> impact(r).targets().size()),
            "age", new Field(Unit.SECOND, r -> impact(r).ageMicros() / 1_000_000.0),
            "normal_x", new Field(Unit.MULTIPLIER, r -> impact(r).normalX()),
            "normal_y", new Field(Unit.MULTIPLIER, r -> impact(r).normalY()),
            "normal_z", new Field(Unit.MULTIPLIER, r -> impact(r).normalZ()),
            "sequence", new Field(Unit.COUNT, r -> impact(r).sequence()), "bounces", new Field(Unit.COUNT, r -> impact(r).bounces()),
            "entity_contacts", new Field(Unit.COUNT, r -> impact(r).entityContacts()), "target_contacts", new Field(Unit.COUNT, r -> impact(r).targetContacts())),
            Map.of("arrived", r -> impact(r).end() == com.imdomestic.chorus.effect.projectile.ProjectileFlight.End.ARRIVED,
                    "caught", r -> impact(r).end() == com.imdomestic.chorus.effect.projectile.ProjectileFlight.End.CAUGHT,
                    "target_lost", r -> impact(r).end() == com.imdomestic.chorus.effect.projectile.ProjectileFlight.End.TARGET_LOST,
                    "entity", r -> impact(r).end() == com.imdomestic.chorus.effect.projectile.ProjectileFlight.End.ENTITY,
                    "block", r -> impact(r).end() == com.imdomestic.chorus.effect.projectile.ProjectileFlight.End.BLOCK,
                    "expired", r -> impact(r).end() == com.imdomestic.chorus.effect.projectile.ProjectileFlight.End.EXPIRED,
                    "unloaded", r -> impact(r).end() == com.imdomestic.chorus.effect.projectile.ProjectileFlight.End.UNLOADED,
                    "terminal", r -> impact(r).terminal(), "bounced", r -> impact(r).end() == com.imdomestic.chorus.effect.projectile.ProjectileFlight.End.BLOCK && !impact(r).terminal(),
                    "pierced", r -> impact(r).end() == com.imdomestic.chorus.effect.projectile.ProjectileFlight.End.ENTITY && !impact(r).terminal()), false, Reference.PROJECTILE_IMPACT);
    public void requireDamageTally() { if (reference != Reference.DAMAGE_TALLY) throw new IllegalArgumentException("Result is not a damage tally handle"); }
    public void requireDamageReceipt() { if (reference != Reference.DAMAGE_RECEIPT) throw new IllegalArgumentException("Result is not a damage receipt"); }
    public static final ResultShape DAMAGE_TALLY = new ResultShape(Map.of(), Map.of(), false, Reference.DAMAGE_TALLY);
    private static com.imdomestic.chorus.effect.combat.DamageTallies.Result tally(RuleEngine.ActionResult result) { return (com.imdomestic.chorus.effect.combat.DamageTallies.Result) result; }
    public static final ResultShape DAMAGE_TALLY_RESULT = new ResultShape(Map.of(
            "attempts", new Field(Unit.COUNT, r -> tally(r).numbers().attempts()), "hits", new Field(Unit.COUNT, r -> tally(r).numbers().hits()),
            "effective_hits", new Field(Unit.COUNT, r -> tally(r).numbers().effectiveHits()), "kills", new Field(Unit.COUNT, r -> tally(r).numbers().kills()),
            "shield_loss", new Field(Unit.DAMAGE, r -> tally(r).numbers().shieldLoss()), "absorption_loss", new Field(Unit.DAMAGE, r -> tally(r).numbers().absorptionLoss()),
            "health_loss", new Field(Unit.DAMAGE, r -> tally(r).numbers().healthLoss()),
            "effective", new Field(Unit.DAMAGE, r -> tally(r).numbers().shieldLoss() + tally(r).numbers().healthLoss()),
            "effective_with_absorption", new Field(Unit.DAMAGE, r -> tally(r).numbers().shieldLoss() + tally(r).numbers().healthLoss() + tally(r).numbers().absorptionLoss())),
            Map.of("available", r -> tally(r).summary().isPresent(), "changed", r -> tally(r).changed()));
    public void requireRetainedCost() { if (reference != Reference.RETAINED_COST) throw new IllegalArgumentException("Result is not a retained cost handle"); }
    public static final ResultShape RETAINED_COST = new ResultShape(Map.of(), Map.of(), false, Reference.RETAINED_COST);
    private static java.util.Optional<Resources.RefundResult> retainedRefund(RuleEngine.ActionResult result) {
        return ((com.imdomestic.chorus.effect.resource.RetainedCosts.Refunded) result).refund();
    }
    public static final ResultShape RETAINED_REFUND = new ResultShape(Map.of(
            "requested", new Field(Unit.CHARGE, r -> retainedRefund(r).map(x -> x.grant().requested()).orElse(0.0)),
            "allowed", new Field(Unit.CHARGE, r -> retainedRefund(r).map(x -> x.grant().scaled()).orElse(0.0)),
            "credited", new Field(Unit.CHARGE, r -> retainedRefund(r).map(x -> x.grant().credited()).orElse(0.0)),
            "overflow", new Field(Unit.CHARGE, r -> retainedRefund(r).map(x -> x.grant().overflow()).orElse(0.0))),
            Map.of("available", r -> retainedRefund(r).isPresent(), "changed", r -> retainedRefund(r).map(x -> x.grant().credited() > 0).orElse(false)));
    public void requireDamageGroup() { if (reference != Reference.DAMAGE_GROUP) throw new IllegalArgumentException("Result is not a damage group"); }
    public static final ResultShape DAMAGE_GROUP = new ResultShape(Map.of(), Map.of(), false, Reference.DAMAGE_GROUP);
    public void requireDamageBatch() { if (reference != Reference.DAMAGE_BATCH) throw new IllegalArgumentException("Result is not a damage batch"); }
    public static final ResultShape DAMAGE_BATCH = new ResultShape(Map.of(), Map.of(), false, Reference.DAMAGE_BATCH);
    public static final ResultShape SHOT = new ResultShape(Map.of("pellets", new Field(Unit.COUNT,
            r -> ((com.imdomestic.chorus.effect.projectile.ShotGroups.Handle) r).pellets())), Map.of(), false, Reference.SHOT);
    public static final ResultShape SHOT_IMPACT = new ResultShape(PROJECTILE_IMPACT.fields(), PROJECTILE_IMPACT.flags(), false, Reference.SHOT_IMPACT);
    public void requireShot() { if (reference != Reference.SHOT) throw new IllegalArgumentException("Result is not a shot handle"); }
    public void requireShotImpact() { if (reference != Reference.SHOT_IMPACT) throw new IllegalArgumentException("Result is not a grouped pellet impact"); }
    public static final ResultShape DIRECTION = new ResultShape(Map.of(), Map.of(
            "available", result -> ((com.imdomestic.chorus.effect.target.DirectionResult) result).direction().isPresent(),
            "missing", result -> ((com.imdomestic.chorus.effect.target.DirectionResult) result).direction().isEmpty()), false, Reference.DIRECTION);
    public void requirePosition() { if (reference != Reference.POSITION && reference != Reference.PROJECTILE_IMPACT && reference != Reference.SHOT_IMPACT && reference != Reference.PICKUP_CONTACT) throw new IllegalArgumentException("Result is not a position capture"); }
    public java.util.Optional<com.imdomestic.chorus.effect.target.WorldPosition> position(RuleEngine.ActionResult result) {
        requirePosition(); return ((com.imdomestic.chorus.effect.target.PositionResult) result).position();
    }
    private static com.imdomestic.chorus.effect.combat.ShieldRestoration.Receipt shieldRestoration(RuleEngine.ActionResult result) { return (com.imdomestic.chorus.effect.combat.ShieldRestoration.Receipt) result; }
    public static final ResultShape SHIELD_RESTORATION = new ResultShape(Map.of(
            "requested", new Field(Unit.DAMAGE, r -> shieldRestoration(r).requested()), "maximum", new Field(Unit.DAMAGE, r -> shieldRestoration(r).maximum()),
            "before", new Field(Unit.DAMAGE, r -> shieldRestoration(r).before()), "after", new Field(Unit.DAMAGE, r -> shieldRestoration(r).after()),
            "effective", new Field(Unit.DAMAGE, r -> shieldRestoration(r).effective()), "overflow", new Field(Unit.DAMAGE, r -> shieldRestoration(r).overflow())),
            Map.of("changed", r -> shieldRestoration(r).effective() > 0, "full", r -> shieldRestoration(r).after() >= shieldRestoration(r).maximum()));
    public static final ResultShape POSITION = new ResultShape(Map.of(), Map.of(
            "available", result -> ((com.imdomestic.chorus.effect.target.PositionResult) result).position().isPresent(),
            "missing", result -> ((com.imdomestic.chorus.effect.target.PositionResult) result).position().isEmpty()), false, Reference.POSITION);
    private static com.imdomestic.chorus.effect.target.EntityQuery.Result entity(RuleEngine.ActionResult result) {
        return (com.imdomestic.chorus.effect.target.EntityQuery.Result) result;
    }
    public void requireEntityObservation() { if (reference != Reference.ENTITY_OBSERVATION) throw new IllegalArgumentException("Result is not an entity observation"); }
    public boolean entityTag(RuleEngine.ActionResult result, com.imdomestic.chorus.effect.target.EntityQuery.TagSource source, String tag) {
        requireEntityObservation(); return entity(result).available().hasTag(source, tag);
    }
    public static final ResultShape ENTITY = new ResultShape(Map.of(
            "health", new Field(Unit.DAMAGE, result -> entity(result).available().health()),
            "max_health", new Field(Unit.DAMAGE, result -> entity(result).available().maximumHealth()),
            "absorption", new Field(Unit.DAMAGE, result -> entity(result).available().absorption()),
            "health_fraction", new Field(Unit.MULTIPLIER, result -> entity(result).available().health() / entity(result).available().maximumHealth())),
            Map.ofEntries(
                    Map.entry("available", result -> entity(result).view().isPresent()), Map.entry("missing", result -> entity(result).view().isEmpty()),
                    Map.entry("alive", result -> entity(result).available().alive()), Map.entry("player", result -> entity(result).available().player()),
                    Map.entry("movement_observed", result -> entity(result).view().flatMap(com.imdomestic.chorus.effect.target.EntityQuery.View::movement).isPresent()),
                    Map.entry("on_ground", result -> entity(result).available().observedMovement().onGround()),
                    Map.entry("sprinting", result -> entity(result).available().observedMovement().sprinting()),
                    Map.entry("crouching", result -> entity(result).available().observedMovement().crouching()),
                    Map.entry("swimming", result -> entity(result).available().observedMovement().swimming()),
                    Map.entry("fall_flying", result -> entity(result).available().observedMovement().fallFlying()),
                    Map.entry("passenger", result -> entity(result).available().observedMovement().passenger()),
                    Map.entry("sleeping", result -> entity(result).available().observedMovement().sleeping())), false, Reference.ENTITY_OBSERVATION);
    public java.util.Optional<DamageSnapshot> optionalSnapshot(RuleEngine.ActionResult result) {
        requireSnapshot(); return result instanceof DamageSnapshot.Stored stored ? stored.snapshot() : java.util.Optional.of((DamageSnapshot) result);
    }
    public DamageSnapshot snapshot(RuleEngine.ActionResult result) { return optionalSnapshot(result).orElseThrow(() -> new IllegalStateException("Stored damage snapshot is unavailable")); }
    public static final ResultShape DAMAGE_SNAPSHOT = new ResultShape(Map.of("base_damage", new Field(Unit.DAMAGE,
            result -> ((DamageSnapshot) result).attack().amount())), Map.of(), false, Reference.DAMAGE_SNAPSHOT);
    public static final ResultShape STORED_DAMAGE_SNAPSHOT = new ResultShape(Map.of("base_damage", new Field(Unit.DAMAGE,
            result -> DAMAGE_SNAPSHOT.snapshot(result).attack().amount())), Map.of(
            "available", result -> ((DamageSnapshot.Stored) result).snapshot().isPresent(),
            "missing", result -> ((DamageSnapshot.Stored) result).snapshot().isEmpty()), false, Reference.DAMAGE_SNAPSHOT);
    public String target(RuleEngine.ActionResult result) { requireTarget(); return ((com.imdomestic.chorus.effect.target.Targets.Reference) result).entity(); }
    public java.util.List<? extends com.imdomestic.chorus.effect.target.Targets.Reference> targets(RuleEngine.ActionResult result) {
        requireTargets(); return ((com.imdomestic.chorus.effect.target.Targets.Collection) result).targets();
    }
    public static final ResultShape TARGET_IDENTITY = new ResultShape(Map.of(), Map.of(), false, Reference.TARGET);
    public static final ResultShape TARGET_IDENTITIES = new ResultShape(Map.of("count", new Field(Unit.COUNT,
            result -> ((com.imdomestic.chorus.effect.target.Targets.Identities) result).targets().size())), Map.of(), false, Reference.TARGET_IDENTITIES);
    private static com.imdomestic.chorus.effect.target.Targets.Difference difference(RuleEngine.ActionResult result) { return (com.imdomestic.chorus.effect.target.Targets.Difference) result; }
    public static final ResultShape TARGET_DIFFERENCE = new ResultShape(Map.of(
            "before_count", new Field(Unit.COUNT, r -> difference(r).before().targets().size()),
            "after_count", new Field(Unit.COUNT, r -> difference(r).after().targets().size()),
            "entered_count", new Field(Unit.COUNT, r -> difference(r).part(com.imdomestic.chorus.effect.target.Targets.Part.ENTERED).targets().size()),
            "exited_count", new Field(Unit.COUNT, r -> difference(r).part(com.imdomestic.chorus.effect.target.Targets.Part.EXITED).targets().size())),
            Map.of("observed", r -> difference(r).observed(), "changed", r -> difference(r).changed()), false, Reference.TARGET_DIFFERENCE);
    public static final ResultShape TARGET = new ResultShape(Map.of("distance", new Field(Unit.METER,
            result -> ((com.imdomestic.chorus.effect.target.TargetQuery.Target) result).distance())), Map.of(), false, Reference.TARGET);
    public static final ResultShape TARGETS = new ResultShape(Map.of("count", new Field(Unit.COUNT,
            result -> ((com.imdomestic.chorus.effect.target.TargetQuery.Result) result).targets().size())),
            Map.of("available", result -> ((com.imdomestic.chorus.effect.target.TargetQuery.Result) result).outcome()
                    == com.imdomestic.chorus.effect.target.TargetQuery.Outcome.AVAILABLE,
                    "missing_center", result -> ((com.imdomestic.chorus.effect.target.TargetQuery.Result) result).outcome()
                    == com.imdomestic.chorus.effect.target.TargetQuery.Outcome.MISSING_CENTER,
                    "missing_direction", result -> ((com.imdomestic.chorus.effect.target.TargetQuery.Result) result).outcome() == com.imdomestic.chorus.effect.target.TargetQuery.Outcome.MISSING_DIRECTION,
            "missing_relative", result -> ((com.imdomestic.chorus.effect.target.TargetQuery.Result) result).outcome()
                    == com.imdomestic.chorus.effect.target.TargetQuery.Outcome.MISSING_RELATIVE,
                    "wrong_dimension", result -> ((com.imdomestic.chorus.effect.target.TargetQuery.Result) result).outcome()
                    == com.imdomestic.chorus.effect.target.TargetQuery.Outcome.WRONG_DIMENSION), false, Reference.TARGETS);
    public void requireCost() { if (!carriesCost) throw new IllegalArgumentException("Result does not carry a paid cost receipt"); }
    public Resources.CostReceipt cost(RuleEngine.ActionResult result) {
        requireCost();
        if (!(result instanceof Resources.CostResult cost)) throw new IllegalArgumentException("Result violated its cost receipt shape");
        return cost.receipt();
    }
    public void requireFlag(String name) { if (!flags.containsKey(name)) throw new IllegalArgumentException("Unknown result flag: " + name); }
    public boolean flag(String name, RuleEngine.ActionResult result) { requireFlag(name); return flags.get(name).test(result); }
    public Unit unit(String name) { return field(name).unit(); }
    public Measure read(String name, RuleEngine.ActionResult result) {
        Field field = field(name); return new Measure(field.read().applyAsDouble(result), field.unit());
    }
    private Field field(String name) {
        var field = fields.get(name); if (field == null) throw new IllegalArgumentException("Unknown result field: " + name); return field;
    }
    public static final ResultShape EMPTY = new ResultShape(Map.of());
    public static final ResultShape BUFF = new ResultShape(Map.of(
            "requested", new Field(Unit.COUNT, result -> ((Buffs.Receipt) result).requested()),
            "credited", new Field(Unit.COUNT, result -> ((Buffs.Receipt) result).credited()),
            "stored_delta", new Field(Unit.COUNT, result -> ((Buffs.Receipt) result).storedDelta())), Map.of("applied", result -> ((Buffs.Receipt) result).applied()));
    public static final ResultShape RESOURCE = new ResultShape(Map.of(
            "requested", new Field(Unit.CHARGE, result -> ((ResourceResult) result).requested()),
            "scaled", new Field(Unit.CHARGE, result -> ((ResourceResult) result).scaled()),
            "credited", new Field(Unit.CHARGE, result -> ((ResourceResult) result).credited()),
            "overflow", new Field(Unit.CHARGE, result -> ((ResourceResult) result).overflow())));
    public static final ResultShape RESOURCE_SPEND = new ResultShape(Map.of(
            "paid", new Field(Unit.CHARGE, result -> ((com.imdomestic.chorus.effect.resource.Resources.SpendResult) result).receipt().paid()),
            "after", new Field(Unit.CHARGE, result -> ((com.imdomestic.chorus.effect.resource.Resources.SpendResult) result).after().value())),
            Map.of("succeeded", result -> ((com.imdomestic.chorus.effect.resource.Resources.SpendResult) result).succeeded()), true);
    public static final ResultShape RESOURCE_REFUND = new ResultShape(Map.of(
            "requested", new Field(Unit.CHARGE, result -> ((Resources.RefundResult) result).grant().requested()),
            "allowed", new Field(Unit.CHARGE, result -> ((Resources.RefundResult) result).grant().scaled()),
            "credited", new Field(Unit.CHARGE, result -> ((Resources.RefundResult) result).grant().credited()),
            "overflow", new Field(Unit.CHARGE, result -> ((Resources.RefundResult) result).grant().overflow()),
            "paid", new Field(Unit.CHARGE, result -> ((Resources.RefundResult) result).receipt().paid()),
            "claimed", new Field(Unit.CHARGE, result -> ((Resources.RefundResult) result).receipt().refundClaimed()),
            "remaining", new Field(Unit.CHARGE, result -> Resources.remaining(((Resources.RefundResult) result).receipt()))),
            Map.of("changed", result -> ((Resources.RefundResult) result).grant().credited() > 0), true);
    public static final ResultShape STATUS = new ResultShape(Map.of(
            "stacks_before", new Field(Unit.COUNT, result -> ((StatusResult) result).before()),
            "stacks_after", new Field(Unit.COUNT, result -> ((StatusResult) result).after())),
            Map.of("applied", result -> ((StatusResult) result).applied()));
    public static final ResultShape STATUS_CHECK = new ResultShape(Map.of(), Map.of(
            "allowed", result -> ((StatusResult.Checked) result).decision() == StatusResult.Decision.ALLOWED,
            "denied", result -> ((StatusResult.Checked) result).decision() == StatusResult.Decision.DENIED,
            "dead", result -> ((StatusResult.Checked) result).decision() == StatusResult.Decision.DEAD,
            "missing", result -> ((StatusResult.Checked) result).decision() == StatusResult.Decision.MISSING));
    public static final ResultShape HEALING = new ResultShape(Map.of(
            "requested", new Field(Unit.DAMAGE, result -> ((HealingReceipt) result).requested()),
            "offered", new Field(Unit.DAMAGE, result -> ((HealingReceipt) result).offered()),
            "effective", new Field(Unit.DAMAGE, result -> ((HealingReceipt) result).effective()),
            "overheal", new Field(Unit.DAMAGE, result -> ((HealingReceipt) result).overheal())),
            Map.of("applied", result -> ((HealingReceipt) result).applied(), "changed", result -> ((HealingReceipt) result).effective() > 0,
                    "overhealed", result -> ((HealingReceipt) result).overheal() > 0));
    public static final ResultShape DAMAGE = new ResultShape(Map.of(
            "effective", new Field(Unit.DAMAGE, result -> ((DamageReceipt) result).effective(false)),
            "effective_with_absorption", new Field(Unit.DAMAGE, result -> ((DamageReceipt) result).effective(true)),
            "health_loss", new Field(Unit.DAMAGE, result -> ((DamageReceipt) result).healthLoss()),
            "shield_loss", new Field(Unit.DAMAGE, result -> ((DamageReceipt) result).shieldLoss()),
            "absorption_loss", new Field(Unit.DAMAGE, result -> ((DamageReceipt) result).absorptionLoss())),
            Map.of("lethal", result -> ((DamageReceipt) result).lethal(), "death_prevented", result -> ((DamageReceipt) result).deathPrevented(),
                    "immune", result -> ((DamageReceipt) result).outcome() == DamageReceipt.Outcome.IMMUNE,
                    "applied", result -> ((DamageReceipt) result).outcome() == DamageReceipt.Outcome.APPLIED), false, Reference.DAMAGE_RECEIPT);
}
