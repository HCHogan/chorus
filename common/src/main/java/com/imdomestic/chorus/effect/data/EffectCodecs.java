package com.imdomestic.chorus.effect.data;

import static com.imdomestic.chorus.core.codec.DataCodecs.*;

import com.imdomestic.chorus.core.codec.TypeRegistry;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.ability.AbilityDefinition;
import com.imdomestic.chorus.effect.weapon.WeaponDefinition;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.combat.ProcPolicy;
import com.imdomestic.chorus.effect.resource.ResourceDefinition;
import com.imdomestic.chorus.stat.*;
import com.imdomestic.chorus.stat.codec.StatCodecs;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Open type registries plus closed record schemas. Parsing and compilation both return data errors. */
public final class EffectCodecs {
    private EffectCodecs() {}
    private static final Codec<ProcPolicy.Spec> PROC = strict(RecordCodecBuilder.create(i -> i.group(
            ID.listOf().xmap(Set::copyOf, s -> s.stream().sorted().toList()).optionalFieldOf("deny", Set.of()).forGetter(ProcPolicy.Spec::deny),
            enumeration(ProcPolicy.Inherit.class).optionalFieldOf("inherit", ProcPolicy.Inherit.FRESH).forGetter(ProcPolicy.Spec::inherit)
    ).apply(i, ProcPolicy.Spec::new)), Set.of("deny", "inherit"));
    private static final Codec<Evaluation.BoundTarget> BOUND_TARGET = strict(RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("binding").forGetter(Evaluation.BoundTarget::binding)
    ).apply(i, Evaluation.BoundTarget::new)), Set.of("binding"));
    private static final Codec<Evaluation.Target> TARGET = Codec.either(enumeration(Evaluation.BuiltinTarget.class), BOUND_TARGET).xmap(
            value -> value.map(left -> left, right -> right), value -> switch (value) {
                case Evaluation.BuiltinTarget builtin -> Either.left(builtin); case Evaluation.BoundTarget bound -> Either.right(bound);
            });
    private static final Codec<Evaluation.BoundPosition> BOUND_POSITION = strict(RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("position").forGetter(Evaluation.BoundPosition::binding)
    ).apply(i, Evaluation.BoundPosition::new)), Set.of("position"));
    private static final Codec<Evaluation.Center> CENTER = Codec.either(TARGET, BOUND_POSITION).xmap(
            value -> value.map(left -> left, right -> right), value -> switch (value) {
                case Evaluation.Target target -> Either.left(target); case Evaluation.BoundPosition position -> Either.right(position);
            });
    public static final Value ONE = new Value.Constant(1, Unit.COUNT);
    public static final Condition ALWAYS = new Condition.Constant(true);

    public static TypeRegistry<Value> valueTypes(Codec<Value> self) {
        return new TypeRegistry<Value>()
                .register("chorus:ammo", Value.Ammo.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        TARGET.optionalFieldOf("weapon", Evaluation.Target.THIS_WEAPON).forGetter(Value.Ammo::weapon),
                        enumeration(com.imdomestic.chorus.effect.ammo.AmmoState.Field.class).fieldOf("field").forGetter(Value.Ammo::field)).apply(i, Value.Ammo::new)))
                .register("chorus:round", Value.Round.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        self.fieldOf("input").forGetter(Value.Round::input), enumeration(Value.Rounding.class).fieldOf("mode").forGetter(Value.Round::mode)).apply(i, Value.Round::new)))
                .register("chorus:constant", Value.Constant.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        FINITE.fieldOf("value").forGetter(Value.Constant::value), StatCodecs.UNIT.fieldOf("unit").forGetter(Value.Constant::quantity)
                ).apply(i, Value.Constant::new)))
                .register("chorus:result", Value.Result.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("binding").forGetter(Value.Result::binding), Codec.STRING.fieldOf("field").forGetter(Value.Result::field)
                ).apply(i, Value.Result::new)))
                .register("chorus:resource", Value.Resource.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("resource").forGetter(Value.Resource::resource), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Value.Resource::target)
                ).apply(i, Value.Resource::new)))
                .register("chorus:event_number", Value.EventNumber.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("name").forGetter(Value.EventNumber::name), StatCodecs.UNIT.fieldOf("unit").forGetter(Value.EventNumber::quantity)
                ).apply(i, Value.EventNumber::new)))
                .register("chorus:impact_number", Value.ImpactNumber.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("name").forGetter(Value.ImpactNumber::name), StatCodecs.UNIT.fieldOf("unit").forGetter(Value.ImpactNumber::quantity)
                ).apply(i, Value.ImpactNumber::new)))
                .register("chorus:buff_count", Value.BuffCount.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Value.BuffCount::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Value.BuffCount::target)
                ).apply(i, Value.BuffCount::new)))
                .register("chorus:component", Value.Component.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Value.Component::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Value.Component::target),
                        Codec.STRING.fieldOf("component").forGetter(Value.Component::component)).apply(i, Value.Component::new)))
                .register("chorus:by_stacks", Value.ByStacks.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        FINITE.listOf().fieldOf("values").forGetter(Value.ByStacks::values), StatCodecs.UNIT.fieldOf("unit").forGetter(Value.ByStacks::quantity)
                ).apply(i, Value.ByStacks::new)))
                .register("chorus:by_buff_tier", Value.ByBuffTier.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        FINITE.listOf().fieldOf("values").forGetter(Value.ByBuffTier::values), StatCodecs.UNIT.fieldOf("unit").forGetter(Value.ByBuffTier::quantity)
                ).apply(i, Value.ByBuffTier::new)))
                .register("chorus:by_source_tag", Value.BySourceTag.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.unboundedMap(ID, self).fieldOf("values").forGetter(Value.BySourceTag::values)
                ).apply(i, Value.BySourceTag::new)))
                .register("chorus:choose", Value.Choose.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        conditions(self).fieldOf("if").forGetter(Value.Choose::condition),
                        self.fieldOf("then").forGetter(Value.Choose::then), self.fieldOf("else").forGetter(Value.Choose::otherwise)
                ).apply(i, Value.Choose::new)))
                .register("chorus:arithmetic", Value.Arithmetic.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        enumeration(Value.Operator.class).fieldOf("op").forGetter(Value.Arithmetic::operation), self.listOf().fieldOf("of").forGetter(Value.Arithmetic::operands)
                ).apply(i, Value.Arithmetic::new)))
                .register("chorus:scale", Value.Scale.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        self.fieldOf("of").forGetter(Value.Scale::input), FINITE.fieldOf("factor").forGetter(Value.Scale::factor),
                        StatCodecs.UNIT.fieldOf("from").forGetter(Value.Scale::from), StatCodecs.UNIT.fieldOf("to").forGetter(Value.Scale::to)
                ).apply(i, Value.Scale::new)))
                .register("chorus:curve", Value.CurveValue.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        self.fieldOf("of").forGetter(Value.CurveValue::input), StatCodecs.CURVE.fieldOf("curve").forGetter(Value.CurveValue::curve),
                        StatCodecs.UNIT.fieldOf("from").forGetter(Value.CurveValue::from), StatCodecs.UNIT.fieldOf("to").forGetter(Value.CurveValue::to)
                ).apply(i, Value.CurveValue::new)))
                .register("chorus:enhanced", Value.Enhanced.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        self.fieldOf("base").forGetter(Value.Enhanced::base), self.fieldOf("enhanced").forGetter(Value.Enhanced::enhanced)
                ).apply(i, Value.Enhanced::new)))
                .register("chorus:pvp", Value.Mode.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        self.fieldOf("pve").forGetter(Value.Mode::pve), self.fieldOf("pvp").forGetter(Value.Mode::pvp)
                ).apply(i, Value.Mode::new)));
    }
    public static final Codec<Value> VALUE = Codec.recursive("ChorusEffectValue", self -> valueTypes(self).build());

    public static TypeRegistry<Condition> conditionTypes(Codec<Condition> self, Codec<Value> values) {
        return new TypeRegistry<Condition>()
                .register("chorus:constant", Condition.Constant.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.BOOL.fieldOf("value").forGetter(Condition.Constant::value)).apply(i, Condition.Constant::new)))
                .register("chorus:all", Condition.All.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        self.listOf().fieldOf("of").forGetter(Condition.All::of)).apply(i, Condition.All::new)))
                .register("chorus:any", Condition.Any.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        self.listOf().fieldOf("of").forGetter(Condition.Any::of)).apply(i, Condition.Any::new)))
                .register("chorus:not", Condition.Not.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        self.fieldOf("of").forGetter(Condition.Not::value)).apply(i, Condition.Not::new)))
                .register("chorus:source_is", Condition.SourceIs.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        enumeration(Condition.Source.class).fieldOf("source").forGetter(Condition.SourceIs::source)).apply(i, Condition.SourceIs::new)))
                .register("chorus:own_source", Condition.OwnSource.class, MapCodec.unit(new Condition.OwnSource()))
                .register("chorus:weapon_drawn", Condition.WeaponDrawn.class, MapCodec.unit(new Condition.WeaponDrawn()))
                .register("chorus:own_shield", Condition.OwnShield.class, MapCodec.unit(new Condition.OwnShield()))
                .register("chorus:resource_crossed", Condition.ResourceCrossed.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("resource").forGetter(Condition.ResourceCrossed::resource), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Condition.ResourceCrossed::target),
                        FINITE.fieldOf("threshold").forGetter(Condition.ResourceCrossed::threshold), enumeration(Condition.Crossing.class).fieldOf("direction").forGetter(Condition.ResourceCrossed::direction)
                ).apply(i, Condition.ResourceCrossed::new)))
                .register("chorus:event_tag", Condition.EventTag.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("tag").forGetter(Condition.EventTag::tag)).apply(i, Condition.EventTag::new)))
                .register("chorus:layer_tag", Condition.LayerTag.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("tag").forGetter(Condition.LayerTag::tag)).apply(i, Condition.LayerTag::new)))
                .register("chorus:source_tag", Condition.SourceTag.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("tag").forGetter(Condition.SourceTag::tag)).apply(i, Condition.SourceTag::new)))
                .register("chorus:event_reference", Condition.EventReference.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("name").forGetter(Condition.EventReference::name), Codec.STRING.fieldOf("is").forGetter(Condition.EventReference::expected)
                ).apply(i, Condition.EventReference::new)))
                .register("chorus:event_flag", Condition.EventFlag.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("name").forGetter(Condition.EventFlag::name), Codec.BOOL.optionalFieldOf("is", true).forGetter(Condition.EventFlag::expected)).apply(i, Condition.EventFlag::new)))
                .register("chorus:target_is", Condition.TargetIs.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        TARGET.fieldOf("left").forGetter(Condition.TargetIs::left), TARGET.fieldOf("right").forGetter(Condition.TargetIs::right)).apply(i, Condition.TargetIs::new)))
                .register("chorus:result_flag", Condition.ResultFlag.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("binding").forGetter(Condition.ResultFlag::binding), Codec.STRING.fieldOf("field").forGetter(Condition.ResultFlag::field),
                        Codec.BOOL.optionalFieldOf("is", true).forGetter(Condition.ResultFlag::expected)).apply(i, Condition.ResultFlag::new)))
                .register("chorus:has_buff", Condition.HasBuff.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Condition.HasBuff::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Condition.HasBuff::target),
                        Codec.INT.optionalFieldOf("minimum", 1).forGetter(Condition.HasBuff::minimum),
                        enumeration(Condition.BuffMatch.class).optionalFieldOf("match", Condition.BuffMatch.BOUND).forGetter(Condition.HasBuff::match)).apply(i, Condition.HasBuff::new)))
                .register("chorus:has_buff_tag", Condition.HasBuffTag.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("tag").forGetter(Condition.HasBuffTag::tag), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Condition.HasBuffTag::target)
                ).apply(i, Condition.HasBuffTag::new)))
                .register("chorus:has_shield", Condition.HasShield.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.optionalFieldOf("tag").forGetter(Condition.HasShield::tag), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Condition.HasShield::target)
                ).apply(i, Condition.HasShield::new)))
                .register("chorus:compare", Condition.Compare.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        values.fieldOf("left").forGetter(Condition.Compare::left), enumeration(Condition.Comparison.class).fieldOf("op").forGetter(Condition.Compare::operation),
                        values.fieldOf("right").forGetter(Condition.Compare::right)).apply(i, Condition.Compare::new)))
                .register("chorus:own_timer", Condition.OwnTimer.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("name").forGetter(Condition.OwnTimer::name)).apply(i, Condition.OwnTimer::new)))
                .register("chorus:own_buff", Condition.OwnBuff.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        enumeration(Buffs.Reason.class).optionalFieldOf("reason").forGetter(Condition.OwnBuff::reason)).apply(i, Condition.OwnBuff::new)));
    }
    public static Codec<Condition> conditions(Codec<Value> values) {
        return Codec.recursive("ChorusEffectCondition", self -> conditionTypes(self, values).build());
    }
    public static final Codec<Condition> CONDITION = conditions(VALUE);

    private static Codec<TargetArea> areas(Codec<Value> values) {
        return new TypeRegistry<TargetArea>()
                .register("chorus:sphere", TargetArea.Sphere.class, RecordCodecBuilder.mapCodec(i -> i.group(values.fieldOf("radius").forGetter(TargetArea.Sphere::radius)).apply(i, TargetArea.Sphere::new)))
                .register("chorus:cylinder", TargetArea.Cylinder.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        values.fieldOf("radius").forGetter(TargetArea.Cylinder::radius), values.fieldOf("height").forGetter(TargetArea.Cylinder::height)).apply(i, TargetArea.Cylinder::new)))
                .register("chorus:cone", TargetArea.Cone.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        values.fieldOf("length").forGetter(TargetArea.Cone::length), values.fieldOf("radius").forGetter(TargetArea.Cone::radius), Codec.STRING.fieldOf("direction").forGetter(TargetArea.Cone::direction)).apply(i, TargetArea.Cone::new)))
                .build();
    }
    private record SelectionData(Evaluation.Center center, java.util.Optional<Value> radius, java.util.Optional<TargetArea> area,
            com.imdomestic.chorus.effect.target.TargetQuery.Relation relation, Evaluation.Target relativeTo, boolean includeCenter,
            List<Evaluation.Target> exclude, com.imdomestic.chorus.effect.target.TargetQuery.Order order, java.util.Optional<Value> limit,
            com.imdomestic.chorus.effect.target.TargetQuery.Anchor centerAnchor, com.imdomestic.chorus.effect.target.TargetQuery.Anchor targetAnchor, boolean lineOfSight) {
        Action.SelectTargets action() {
            if (radius.isPresent() == area.isPresent()) throw new IllegalArgumentException("Select targets requires exactly one of radius or area");
            return new Action.SelectTargets(center, area.orElseGet(() -> new TargetArea.Sphere(radius.orElseThrow())), relation, relativeTo, includeCenter, exclude, order, limit, centerAnchor, targetAnchor, lineOfSight);
        }
        static SelectionData of(Action.SelectTargets action) {
            return new SelectionData(action.center(), java.util.Optional.empty(), java.util.Optional.of(action.area()), action.relation(), action.relativeTo(), action.includeCenter(), action.exclude(), action.order(), action.limit(), action.centerAnchor(), action.targetAnchor(), action.lineOfSight());
        }
    }
    private static MapCodec<Action.SelectTargets> selection(Codec<Value> values) {
        return RecordCodecBuilder.<SelectionData>mapCodec(i -> i.group(
                CENTER.optionalFieldOf("center", Evaluation.Target.SELF).forGetter(SelectionData::center), values.optionalFieldOf("radius").forGetter(SelectionData::radius),
                areas(values).optionalFieldOf("area").forGetter(SelectionData::area),
                enumeration(com.imdomestic.chorus.effect.target.TargetQuery.Relation.class).optionalFieldOf("relation", com.imdomestic.chorus.effect.target.TargetQuery.Relation.ANY).forGetter(SelectionData::relation),
                TARGET.optionalFieldOf("relative_to", Evaluation.Target.SELF).forGetter(SelectionData::relativeTo), Codec.BOOL.optionalFieldOf("include_center", false).forGetter(SelectionData::includeCenter),
                TARGET.listOf().optionalFieldOf("exclude", List.of()).forGetter(SelectionData::exclude),
                enumeration(com.imdomestic.chorus.effect.target.TargetQuery.Order.class).optionalFieldOf("order", com.imdomestic.chorus.effect.target.TargetQuery.Order.IDENTITY).forGetter(SelectionData::order),
                values.optionalFieldOf("limit").forGetter(SelectionData::limit),
                enumeration(com.imdomestic.chorus.effect.target.TargetQuery.Anchor.class).optionalFieldOf("center_anchor", com.imdomestic.chorus.effect.target.TargetQuery.Anchor.FEET).forGetter(SelectionData::centerAnchor),
                enumeration(com.imdomestic.chorus.effect.target.TargetQuery.Anchor.class).optionalFieldOf("target_anchor", com.imdomestic.chorus.effect.target.TargetQuery.Anchor.FEET).forGetter(SelectionData::targetAnchor),
                Codec.BOOL.optionalFieldOf("line_of_sight", false).forGetter(SelectionData::lineOfSight)
        ).apply(i, SelectionData::new)).flatXmap(data -> safe(data::action), action -> DataResult.success(SelectionData.of(action)));
    }
    public static TypeRegistry<Action> actionTypes(Codec<Value> values) {
        Codec<AmmoActions.FiniteReserve> finiteReserve = strict(RecordCodecBuilder.create(i -> i.group(
                values.fieldOf("amount").forGetter(AmmoActions.FiniteReserve::amount), values.fieldOf("capacity").forGetter(AmmoActions.FiniteReserve::capacity)
        ).apply(i, AmmoActions.FiniteReserve::new)), Set.of("amount", "capacity"));
        Codec<AmmoActions.ReserveSpec> reserves = Codec.either(Codec.STRING, finiteReserve).flatXmap(
                value -> value.map(name -> name.equals("unlimited") ? DataResult.success(AmmoActions.ReserveSpec.UNLIMITED) : DataResult.error(() -> "Expected unlimited or finite ammunition reserves"),
                        finite -> DataResult.success(new AmmoActions.ReserveSpec(java.util.Optional.of(finite)))),
                value -> DataResult.success(value.finite().<Either<String, AmmoActions.FiniteReserve>>map(Either::right).orElseGet(() -> Either.left("unlimited"))));
        return new TypeRegistry<Action>()
                .register("chorus:begin_shot", ShotActions.Begin.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        values.fieldOf("pellets").forGetter(ShotActions.Begin::pellets), values.fieldOf("lifetime").forGetter(ShotActions.Begin::lifetime)).apply(i, ShotActions.Begin::new)))
                .register("chorus:sample_random", RandomActions.Sample.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        enumeration(com.imdomestic.chorus.effect.random.RandomState.Distribution.class).fieldOf("distribution").forGetter(RandomActions.Sample::distribution),
                        values.fieldOf("lower").forGetter(RandomActions.Sample::lower), values.fieldOf("upper").forGetter(RandomActions.Sample::upper)).apply(i, RandomActions.Sample::new)))
                .register("chorus:initialize_ammo", AmmoActions.Initialize.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        TARGET.optionalFieldOf("weapon", Evaluation.Target.THIS_WEAPON).forGetter(AmmoActions.Initialize::weapon),
                        values.fieldOf("capacity").forGetter(AmmoActions.Initialize::capacity), values.fieldOf("magazine").forGetter(AmmoActions.Initialize::magazine),
                        reserves.fieldOf("reserves").forGetter(AmmoActions.Initialize::reserves),
                        ID.optionalFieldOf("capacity_profile").forGetter(AmmoActions.Initialize::capacityProfile),
                        TARGET.optionalFieldOf("holder", Evaluation.Target.SELF).forGetter(AmmoActions.Initialize::holder)).apply(i, AmmoActions.Initialize::new)))
                .register("chorus:observe_ammo", AmmoActions.Observe.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        TARGET.optionalFieldOf("weapon", Evaluation.Target.THIS_WEAPON).forGetter(AmmoActions.Observe::weapon)).apply(i, AmmoActions.Observe::new)))
                .register("chorus:spend_ammo", AmmoActions.Spend.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        TARGET.optionalFieldOf("weapon", Evaluation.Target.THIS_WEAPON).forGetter(AmmoActions.Spend::weapon),
                        enumeration(com.imdomestic.chorus.effect.ammo.Ammunition.Pool.class).optionalFieldOf("pool", com.imdomestic.chorus.effect.ammo.Ammunition.Pool.MAGAZINE).forGetter(AmmoActions.Spend::pool),
                        values.fieldOf("amount").forGetter(AmmoActions.Spend::amount)).apply(i, AmmoActions.Spend::new)))
                .register("chorus:refill_magazine", AmmoActions.Refill.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        TARGET.optionalFieldOf("weapon", Evaluation.Target.THIS_WEAPON).forGetter(AmmoActions.Refill::weapon), values.optionalFieldOf("amount").forGetter(AmmoActions.Refill::amount),
                        values.optionalFieldOf("ceiling").forGetter(AmmoActions.Refill::ceiling)).apply(i, AmmoActions.Refill::new)))
                .register("chorus:grant_ammo", AmmoActions.Generate.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        TARGET.optionalFieldOf("weapon", Evaluation.Target.THIS_WEAPON).forGetter(AmmoActions.Generate::weapon),
                        enumeration(com.imdomestic.chorus.effect.ammo.Ammunition.Pool.class).optionalFieldOf("pool", com.imdomestic.chorus.effect.ammo.Ammunition.Pool.MAGAZINE).forGetter(AmmoActions.Generate::pool),
                        values.fieldOf("amount").forGetter(AmmoActions.Generate::amount), values.optionalFieldOf("ceiling").forGetter(AmmoActions.Generate::ceiling)).apply(i, AmmoActions.Generate::new)))
                .register("chorus:restore_shield", Action.RestoreShield.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.RestoreShield::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.RestoreShield::target),
                        values.fieldOf("amount").forGetter(Action.RestoreShield::amount)
                ).apply(i, Action.RestoreShield::new)))
                .register("chorus:grant_buff", Action.GrantBuff.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.GrantBuff::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.GrantBuff::target),
                        values.optionalFieldOf("stacks", ONE).forGetter(Action.GrantBuff::stacks), values.optionalFieldOf("tier", ONE).forGetter(Action.GrantBuff::tier),
                        values.optionalFieldOf("duration").forGetter(Action.GrantBuff::duration)).apply(i, Action.GrantBuff::new)))
                .register("chorus:consume_buff", Action.ConsumeBuff.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.ConsumeBuff::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.ConsumeBuff::target),
                        values.optionalFieldOf("stacks", ONE).forGetter(Action.ConsumeBuff::stacks)).apply(i, Action.ConsumeBuff::new)))
                .register("chorus:remove_buff", Action.RemoveBuff.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.RemoveBuff::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.RemoveBuff::target)).apply(i, Action.RemoveBuff::new)))
                .register("chorus:extend_buff", Action.ExtendBuff.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.ExtendBuff::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.ExtendBuff::target),
                        values.fieldOf("amount").forGetter(Action.ExtendBuff::amount), values.fieldOf("cap").forGetter(Action.ExtendBuff::cap)).apply(i, Action.ExtendBuff::new)))
                .register("chorus:refresh_buff", Action.RefreshBuff.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.RefreshBuff::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.RefreshBuff::target),
                        values.fieldOf("duration").forGetter(Action.RefreshBuff::duration)).apply(i, Action.RefreshBuff::new)))
                .register("chorus:apply_status", Action.ApplyStatus.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.ApplyStatus::buff), TARGET.optionalFieldOf("target", Evaluation.Target.VICTIM).forGetter(Action.ApplyStatus::target),
                        values.optionalFieldOf("stacks", ONE).forGetter(Action.ApplyStatus::stacks), values.optionalFieldOf("tier", ONE).forGetter(Action.ApplyStatus::tier),
                        values.optionalFieldOf("duration").forGetter(Action.ApplyStatus::duration)).apply(i, Action.ApplyStatus::new)))
                .register("chorus:check_status", Action.CheckStatus.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.CheckStatus::buff), TARGET.optionalFieldOf("target", Evaluation.Target.VICTIM).forGetter(Action.CheckStatus::target),
                        values.optionalFieldOf("stacks", ONE).forGetter(Action.CheckStatus::stacks), values.optionalFieldOf("tier", ONE).forGetter(Action.CheckStatus::tier),
                        values.optionalFieldOf("duration").forGetter(Action.CheckStatus::duration), Codec.BOOL.optionalFieldOf("allow_dead", false).forGetter(Action.CheckStatus::allowDead)
                ).apply(i, Action.CheckStatus::new)))
                .register("chorus:damage", Action.Damage.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        TARGET.optionalFieldOf("target", Evaluation.Target.VICTIM).forGetter(Action.Damage::target), values.fieldOf("amount").forGetter(Action.Damage::amount),
                        ID.fieldOf("damage_type").forGetter(Action.Damage::damageType), ID.listOf().xmap(Set::copyOf, s -> s.stream().sorted().toList()).optionalFieldOf("tags", Set.of()).forGetter(Action.Damage::tags),
                        ID.listOf().xmap(Set::copyOf, s -> s.stream().sorted().toList()).optionalFieldOf("kill_tags", Set.of()).forGetter(Action.Damage::killTags),
                        Codec.BOOL.optionalFieldOf("non_lethal", false).forGetter(Action.Damage::nonLethal),
                        ID.optionalFieldOf("scaling_profile").forGetter(Action.Damage::scalingProfile),
                        Codec.unboundedMap(Codec.STRING, values).optionalFieldOf("impact", Map.of()).forGetter(Action.Damage::impact),
                        enumeration(ActionOrigin.class).optionalFieldOf("origin", ActionOrigin.BOUND).forGetter(Action.Damage::origin),
                        ID.optionalFieldOf("shield_scaling_profile").forGetter(Action.Damage::shieldScalingProfile),
                        PROC.optionalFieldOf("proc", ProcPolicy.Spec.DEFAULT).forGetter(Action.Damage::proc)).apply(i, Action.Damage::new)))
                .register("chorus:capture_value", Action.CaptureValue.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        values.fieldOf("value").forGetter(Action.CaptureValue::value)).apply(i, Action.CaptureValue::new)))
                .register("chorus:capture_damage", Action.CaptureDamage.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        values.fieldOf("amount").forGetter(Action.CaptureDamage::amount), ID.fieldOf("damage_type").forGetter(Action.CaptureDamage::damageType),
                        ID.listOf().xmap(Set::copyOf, s -> s.stream().sorted().toList()).optionalFieldOf("tags", Set.of()).forGetter(Action.CaptureDamage::tags),
                        ID.listOf().xmap(Set::copyOf, s -> s.stream().sorted().toList()).optionalFieldOf("kill_tags", Set.of()).forGetter(Action.CaptureDamage::killTags),
                        Codec.BOOL.optionalFieldOf("non_lethal", false).forGetter(Action.CaptureDamage::nonLethal),
                        ID.fieldOf("scaling_profile").forGetter(Action.CaptureDamage::scalingProfile),
                        enumeration(ActionOrigin.class).optionalFieldOf("origin", ActionOrigin.BOUND).forGetter(Action.CaptureDamage::origin),
                        ID.optionalFieldOf("shield_scaling_profile").forGetter(Action.CaptureDamage::shieldScalingProfile),
                        PROC.optionalFieldOf("proc", ProcPolicy.Spec.DEFAULT).forGetter(Action.CaptureDamage::proc)).apply(i, Action.CaptureDamage::new)))
                .register("chorus:damage_snapshot", Action.DamageCaptured.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("snapshot").forGetter(Action.DamageCaptured::snapshot),
                        TARGET.optionalFieldOf("target", Evaluation.Target.VICTIM).forGetter(Action.DamageCaptured::target),
                        Codec.unboundedMap(Codec.STRING, values).optionalFieldOf("impact", Map.of()).forGetter(Action.DamageCaptured::impact),
                        Codec.STRING.optionalFieldOf("pellet").forGetter(Action.DamageCaptured::pellet)).apply(i, Action.DamageCaptured::new)))
                .register("chorus:heal", Action.Heal.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.Heal::target), values.fieldOf("amount").forGetter(Action.Heal::amount),
                        ID.listOf().xmap(Set::copyOf, s -> s.stream().sorted().toList()).optionalFieldOf("tags", Set.of()).forGetter(Action.Heal::tags),
                        enumeration(ActionOrigin.class).optionalFieldOf("origin", ActionOrigin.BOUND).forGetter(Action.Heal::origin)
                ).apply(i, Action.Heal::new)))
                .register("chorus:update_component", Action.UpdateComponent.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.UpdateComponent::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.UpdateComponent::target),
                        Codec.STRING.fieldOf("component").forGetter(Action.UpdateComponent::component), enumeration(BuffComponents.Update.class).fieldOf("op").forGetter(Action.UpdateComponent::operation),
                        values.fieldOf("value").forGetter(Action.UpdateComponent::value), Codec.STRING.optionalFieldOf("once_set").forGetter(Action.UpdateComponent::onceSet),
                        Codec.STRING.optionalFieldOf("event_reference").forGetter(Action.UpdateComponent::eventReference)).apply(i, Action.UpdateComponent::new)))
                .register("chorus:grant_resource", Action.GrantResource.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("resource").forGetter(Action.GrantResource::resource), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.GrantResource::target),
                        values.fieldOf("requested").forGetter(Action.GrantResource::requested), values.optionalFieldOf("scaled").forGetter(Action.GrantResource::scaled)).apply(i, Action.GrantResource::new)))
                .register("chorus:initialize_resource", Action.InitializeResource.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("resource").forGetter(Action.InitializeResource::resource), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.InitializeResource::target)
                ).apply(i, Action.InitializeResource::new)))
                .register("chorus:spend_resource", Action.SpendResource.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("resource").forGetter(Action.SpendResource::resource), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.SpendResource::target),
                        values.fieldOf("amount").forGetter(Action.SpendResource::amount), Codec.STRING.fieldOf("payment").forGetter(Action.SpendResource::payment)
                ).apply(i, Action.SpendResource::new)))
                .register("chorus:refund_cost", Action.RefundCost.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("cost").forGetter(Action.RefundCost::cost), values.fieldOf("fraction").forGetter(Action.RefundCost::fraction)
                ).apply(i, Action.RefundCost::new)))
                .register("chorus:grant_full_charge", Action.GrantFullCharge.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("resource").forGetter(Action.GrantFullCharge::resource), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.GrantFullCharge::target),
                        values.optionalFieldOf("charges", ONE).forGetter(Action.GrantFullCharge::charges)
                ).apply(i, Action.GrantFullCharge::new)))
                .register("chorus:schedule", Action.Schedule.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("name").forGetter(Action.Schedule::name), ID.fieldOf("event").forGetter(Action.Schedule::event),
                        values.fieldOf("delay").forGetter(Action.Schedule::delay), values.optionalFieldOf("interval").forGetter(Action.Schedule::interval),
                        Codec.INT.optionalFieldOf("repeat", 1).forGetter(Action.Schedule::repeat), enumeration(Action.TimerPolicy.class).optionalFieldOf("policy", Action.TimerPolicy.KEEP).forGetter(Action.Schedule::policy)
                ).apply(i, Action.Schedule::new)))
                .register("chorus:cancel_timer", Action.CancelTimer.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("name").forGetter(Action.CancelTimer::name)).apply(i, Action.CancelTimer::new)))
                .register("chorus:emit", Action.Emit.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("event").forGetter(Action.Emit::event)).apply(i, Action.Emit::new)))
                .register("chorus:inspect_entity", Action.InspectEntity.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        TARGET.optionalFieldOf("target", Evaluation.Target.VICTIM).forGetter(Action.InspectEntity::target)
                ).apply(i, Action.InspectEntity::new)))
                .register("chorus:read_targets", Action.ReadTargets.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.ReadTargets::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.ReadTargets::target),
                        Codec.STRING.fieldOf("component").forGetter(Action.ReadTargets::component)
                ).apply(i, Action.ReadTargets::new)))
                .register("chorus:sync_targets", Action.SyncTargets.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.SyncTargets::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.SyncTargets::target),
                        Codec.STRING.fieldOf("component").forGetter(Action.SyncTargets::component), Codec.STRING.fieldOf("targets").forGetter(Action.SyncTargets::targets)
                ).apply(i, Action.SyncTargets::new)))
                .register("chorus:difference_targets", Action.DifferenceTargets.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("binding").forGetter(Action.DifferenceTargets::binding),
                        enumeration(com.imdomestic.chorus.effect.target.Targets.Part.class).fieldOf("part").forGetter(Action.DifferenceTargets::part)
                ).apply(i, Action.DifferenceTargets::new)))
                .register("chorus:read_position", Action.ReadPosition.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.ReadPosition::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.ReadPosition::target),
                        Codec.STRING.fieldOf("component").forGetter(Action.ReadPosition::component)
                ).apply(i, Action.ReadPosition::new)))
                .register("chorus:write_position", Action.WritePosition.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("buff").forGetter(Action.WritePosition::buff), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.WritePosition::target),
                        Codec.STRING.fieldOf("component").forGetter(Action.WritePosition::component), Codec.STRING.fieldOf("position").forGetter(Action.WritePosition::position)
                ).apply(i, Action.WritePosition::new)))
                .register("chorus:capture_position", Action.CapturePosition.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.CapturePosition::target),
                        enumeration(com.imdomestic.chorus.effect.target.TargetQuery.Anchor.class).optionalFieldOf("anchor", com.imdomestic.chorus.effect.target.TargetQuery.Anchor.FEET).forGetter(Action.CapturePosition::anchor)
                ).apply(i, Action.CapturePosition::new)))
                .register("chorus:capture_direction", Action.CaptureDirection.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.CaptureDirection::target)).apply(i, Action.CaptureDirection::new)))
                .register("chorus:select_targets", Action.SelectTargets.class, selection(values))
                .register("chorus:play_cue", Action.Cue.class, RecordCodecBuilder.mapCodec(i -> i.group(
                        ID.fieldOf("cue").forGetter(Action.Cue::cue), TARGET.optionalFieldOf("target", Evaluation.Target.SELF).forGetter(Action.Cue::target)).apply(i, Action.Cue::new)));
    }
    public static final Codec<Action> ACTION = actionTypes(VALUE).build();
    private record ResourceData(String id, double capacity, double initial, double baseRate, List<Double> thresholds, java.util.Optional<String> rateProfile) {}
    public static final Codec<ResourceDefinition> RESOURCE = strict(RecordCodecBuilder.<ResourceData>create(i -> i.group(
            ID.fieldOf("id").forGetter(ResourceData::id), FINITE.fieldOf("capacity").forGetter(ResourceData::capacity),
            FINITE.fieldOf("initial").forGetter(ResourceData::initial), FINITE.optionalFieldOf("base_rate", 0.0).forGetter(ResourceData::baseRate),
            FINITE.listOf().optionalFieldOf("thresholds", List.of()).forGetter(ResourceData::thresholds), ID.optionalFieldOf("rate_profile").forGetter(ResourceData::rateProfile)
    ).apply(i, ResourceData::new)), Set.of("id", "capacity", "initial", "base_rate", "thresholds", "rate_profile")).flatXmap(
            data -> safe(() -> new ResourceDefinition(data.id(), data.capacity(), data.initial(), data.baseRate(), data.thresholds(), data.rateProfile())),
            value -> DataResult.success(new ResourceData(value.id(), value.capacity(), value.initial(), value.baseRate(), value.thresholds(), value.rateProfile())));

    public static Codec<EffectProgram> program(Codec<Value> values, Codec<Condition> conditions, Codec<Action> actions, Codec<CalculationProfile> profiles) {
        Codec<EffectProgram.Instruction> bound = strict(RecordCodecBuilder.create(i -> i.group(
                actions.fieldOf("action").forGetter(EffectProgram.Instruction::action), Codec.STRING.fieldOf("as").forGetter(EffectProgram.Instruction::bind)
        ).apply(i, EffectProgram.Instruction::new)), Set.of("action", "as"));
        Codec<EffectProgram.Instruction> instruction = Codec.either(actions, bound).xmap(
                value -> value.map(action -> new EffectProgram.Instruction(action, ""), instructionValue -> instructionValue),
                value -> value.bind().isEmpty() ? Either.left(value.action()) : Either.right(value));
        Codec<EffectProgram.Step> step = Codec.recursive("ChorusEffectStep", self -> {
            Codec<EffectProgram.Branch> branch = strict(RecordCodecBuilder.create(i -> i.group(
                    conditions.fieldOf("if").forGetter(EffectProgram.Branch::condition), self.listOf().fieldOf("then").forGetter(EffectProgram.Branch::then),
                    self.listOf().optionalFieldOf("else", List.of()).forGetter(EffectProgram.Branch::otherwise)
            ).apply(i, EffectProgram.Branch::new)), Set.of("if", "then", "else"));
            Codec<EffectProgram.ForEach> loop = strict(RecordCodecBuilder.create(i -> i.group(
                    Codec.STRING.fieldOf("for_each").forGetter(EffectProgram.ForEach::collection), Codec.STRING.fieldOf("as").forGetter(EffectProgram.ForEach::bind),
                    self.listOf().fieldOf("do").forGetter(EffectProgram.ForEach::body)
            ).apply(i, EffectProgram.ForEach::new)), Set.of("for_each", "as", "do"));
            Codec<EffectProgram.After> after = strict(RecordCodecBuilder.create(i -> i.group(
                    values.fieldOf("after").forGetter(EffectProgram.After::delay),
                    enumeration(com.imdomestic.chorus.effect.EffectContinuations.Lifetime.class).optionalFieldOf("lifetime", com.imdomestic.chorus.effect.EffectContinuations.Lifetime.SOURCE).forGetter(EffectProgram.After::lifetime),
                    self.listOf().fieldOf("do").forGetter(EffectProgram.After::body)
            ).apply(i, EffectProgram.After::new)), Set.of("after", "lifetime", "do"));
            Codec<ProjectileSpec.LimitSpec> collisionLimit = Codec.either(Codec.STRING, values).flatXmap(
                    value -> value.map(name -> name.equals("unlimited") ? DataResult.success(ProjectileSpec.LimitSpec.UNLIMITED) : DataResult.error(() -> "Expected unlimited or a count expression"),
                            expression -> DataResult.success(new ProjectileSpec.LimitSpec(java.util.Optional.of(expression)))),
                    limit -> DataResult.success(limit.value().<Either<String, Value>>map(Either::right).orElseGet(() -> Either.left("unlimited"))));
            Codec<ProjectileSpec.Collisions> collisions = strict(RecordCodecBuilder.create(i -> i.group(
                    collisionLimit.optionalFieldOf("block_bounces", ProjectileSpec.LimitSpec.ZERO).forGetter(ProjectileSpec.Collisions::blockBounces),
                    collisionLimit.optionalFieldOf("entity_pierces", ProjectileSpec.LimitSpec.ZERO).forGetter(ProjectileSpec.Collisions::entityPierces),
                    collisionLimit.optionalFieldOf("max_hits_per_target", ProjectileSpec.LimitSpec.ONE).forGetter(ProjectileSpec.Collisions::hitsPerTarget),
                    values.optionalFieldOf("restitution", new Value.Constant(1, Unit.MULTIPLIER)).forGetter(ProjectileSpec.Collisions::restitution)
            ).apply(i, ProjectileSpec.Collisions::new)), Set.of("block_bounces", "entity_pierces", "max_hits_per_target", "restitution"));
            Codec<ProjectileSpec.Tracking> tracking = strict(RecordCodecBuilder.create(i -> i.group(
                    values.fieldOf("radius").forGetter(ProjectileSpec.Tracking::radius), values.fieldOf("turn_rate").forGetter(ProjectileSpec.Tracking::turnRate),
                    values.optionalFieldOf("acquisition_angle", new Value.Constant(180, ProjectileSpec.ANGLE)).forGetter(ProjectileSpec.Tracking::acquisitionAngle),
                    enumeration(com.imdomestic.chorus.effect.target.TargetQuery.Anchor.class).optionalFieldOf("target_anchor", com.imdomestic.chorus.effect.target.TargetQuery.Anchor.BODY).forGetter(ProjectileSpec.Tracking::anchor),
                    enumeration(com.imdomestic.chorus.effect.target.TargetQuery.Relation.class).optionalFieldOf("relation", com.imdomestic.chorus.effect.target.TargetQuery.Relation.NOT_ALLIED).forGetter(ProjectileSpec.Tracking::relation),
                    Codec.BOOL.optionalFieldOf("line_of_sight", true).forGetter(ProjectileSpec.Tracking::lineOfSight),
                    Codec.BOOL.optionalFieldOf("redirect_on_contact", false).forGetter(ProjectileSpec.Tracking::redirectOnContact)
            ).apply(i, ProjectileSpec.Tracking::new)), Set.of("radius", "turn_rate", "acquisition_angle", "target_anchor", "relation", "line_of_sight", "redirect_on_contact"));
            Codec<ProjectileSpec> projectileSpec = strict(RecordCodecBuilder.create(i -> i.group(
                    Codec.STRING.fieldOf("position").forGetter(ProjectileSpec::position), Codec.STRING.fieldOf("direction").forGetter(ProjectileSpec::direction),
                    values.fieldOf("speed").forGetter(ProjectileSpec::speed), values.fieldOf("gravity").forGetter(ProjectileSpec::gravity),
                    values.fieldOf("drag").forGetter(ProjectileSpec::drag), values.fieldOf("lifetime").forGetter(ProjectileSpec::lifetime),
                    collisions.optionalFieldOf("collision", ProjectileSpec.Collisions.STOP).forGetter(ProjectileSpec::collision),
                    tracking.optionalFieldOf("tracking").forGetter(ProjectileSpec::tracking)
            ).apply(i, ProjectileSpec::new)), Set.of("position", "direction", "speed", "gravity", "drag", "lifetime", "collision", "tracking"));
            Codec<ShotActions.Membership> membership = strict(RecordCodecBuilder.create(i -> i.group(
                    Codec.STRING.fieldOf("binding").forGetter(ShotActions.Membership::binding), values.fieldOf("pellet").forGetter(ShotActions.Membership::pellet)
            ).apply(i, ShotActions.Membership::new)), Set.of("binding", "pellet"));
            Codec<EffectProgram.Projectile> projectile = strict(RecordCodecBuilder.create(i -> i.group(
                    projectileSpec.fieldOf("projectile").forGetter(EffectProgram.Projectile::spec), Codec.STRING.fieldOf("as").forGetter(EffectProgram.Projectile::bind),
                    self.listOf().fieldOf("do").forGetter(EffectProgram.Projectile::body), membership.optionalFieldOf("shot").forGetter(EffectProgram.Projectile::shot)
            ).apply(i, EffectProgram.Projectile::new)), Set.of("projectile", "as", "do", "shot"));
            return Codec.either(projectile, Codec.either(instruction, Codec.either(branch, Codec.either(loop, after)))).xmap(
                    value -> value.map(p -> p, nested -> nested.map(left -> left, right -> right.map(b -> b, inner -> inner.map(l -> l, a -> a)))),
                    value -> switch (value) {
                        case EffectProgram.Projectile launch -> Either.left(launch);
                        case EffectProgram.Instruction execute -> Either.right(Either.left(execute)); case EffectProgram.Branch choose -> Either.right(Either.right(Either.left(choose)));
                        case EffectProgram.ForEach each -> Either.right(Either.right(Either.right(Either.left(each))));
                        case EffectProgram.After later -> Either.right(Either.right(Either.right(Either.right(later))));
                    });
        });
        Codec<EffectProgram.Rule> rule = strict(RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(EffectProgram.Rule::id), ID.fieldOf("on").forGetter(EffectProgram.Rule::on),
                conditions.optionalFieldOf("if", ALWAYS).forGetter(EffectProgram.Rule::condition), step.listOf().fieldOf("do").forGetter(EffectProgram.Rule::actions),
                enumeration(EffectProgram.ReactionBinding.class).optionalFieldOf("binding", EffectProgram.ReactionBinding.CURRENT_OWNER_BUNDLE).forGetter(EffectProgram.Rule::binding),
                ID.optionalFieldOf("proc_key").forGetter(EffectProgram.Rule::procKey)
        ).apply(i, EffectProgram.Rule::new)), Set.of("id", "on", "if", "do", "binding", "proc_key"));
        Codec<EffectProgram.Modifier> modifier = strict(RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(EffectProgram.Modifier::id), ID.fieldOf("profile").forGetter(EffectProgram.Modifier::profile),
                Codec.STRING.fieldOf("stage").forGetter(EffectProgram.Modifier::stage), Codec.STRING.fieldOf("group").forGetter(EffectProgram.Modifier::group),
                enumeration(NumericContribution.Operation.class).fieldOf("op").forGetter(EffectProgram.Modifier::operation), values.fieldOf("value").forGetter(EffectProgram.Modifier::value),
                Codec.STRING.optionalFieldOf("percent_of", "").forGetter(EffectProgram.Modifier::percentOf), ID.fieldOf("stacking_key").forGetter(EffectProgram.Modifier::stackingKey),
                Codec.INT.optionalFieldOf("priority", 0).forGetter(EffectProgram.Modifier::priority), conditions.optionalFieldOf("if", ALWAYS).forGetter(EffectProgram.Modifier::condition),
                Codec.STRING.fieldOf("reference").forGetter(EffectProgram.Modifier::reference), enumeration(NumericContribution.Confidence.class).fieldOf("confidence").forGetter(EffectProgram.Modifier::confidence),
                enumeration(EffectProgram.Multiplicity.class).optionalFieldOf("multiplicity", EffectProgram.Multiplicity.INSTANCE).forGetter(EffectProgram.Modifier::multiplicity),
                enumeration(EffectProgram.Evaluate.class).optionalFieldOf("evaluate", EffectProgram.Evaluate.ON_USE).forGetter(EffectProgram.Modifier::evaluate)
        ).apply(i, EffectProgram.Modifier::new)), Set.of("id", "profile", "stage", "group", "op", "value", "percent_of", "stacking_key", "priority", "if", "reference", "confidence", "multiplicity", "evaluate"));
        Codec<EffectProgram.HealthRecovery> recovery = strict(RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(EffectProgram.HealthRecovery::id), ID.fieldOf("channel").forGetter(EffectProgram.HealthRecovery::channel),
                values.fieldOf("rate").forGetter(EffectProgram.HealthRecovery::rate), Codec.INT.optionalFieldOf("priority", 0).forGetter(EffectProgram.HealthRecovery::priority),
                conditions.optionalFieldOf("if", ALWAYS).forGetter(EffectProgram.HealthRecovery::condition),
                ID.listOf().xmap(Set::copyOf, s -> s.stream().sorted().toList()).optionalFieldOf("tags", Set.of()).forGetter(EffectProgram.HealthRecovery::tags)
        ).apply(i, EffectProgram.HealthRecovery::new)), Set.of("id", "channel", "rate", "priority", "if", "tags"));
        Codec<AbilityDefinition.Replacement> replacement = strict(RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(AbilityDefinition.Replacement::id), ID.fieldOf("slot").forGetter(AbilityDefinition.Replacement::slot),
                ID.optionalFieldOf("ability").forGetter(AbilityDefinition.Replacement::ability), ID.fieldOf("replace_with").forGetter(AbilityDefinition.Replacement::replaceWith),
                Codec.INT.optionalFieldOf("priority", 0).forGetter(AbilityDefinition.Replacement::priority), conditions.optionalFieldOf("if", ALWAYS).forGetter(AbilityDefinition.Replacement::condition)
        ).apply(i, AbilityDefinition.Replacement::new)), Set.of("id", "slot", "ability", "replace_with", "priority", "if"));
        Codec<AbilityDefinition.Cost> cost = strict(RecordCodecBuilder.create(i -> i.group(
                ID.fieldOf("resource").forGetter(AbilityDefinition.Cost::resource), values.fieldOf("amount").forGetter(AbilityDefinition.Cost::amount),
                ID.optionalFieldOf("profile").forGetter(AbilityDefinition.Cost::profile)
        ).apply(i, AbilityDefinition.Cost::new)), Set.of("resource", "amount", "profile"));
        Codec<AbilityDefinition.Parameter> parameter = strict(RecordCodecBuilder.create(i -> i.group(
                values.fieldOf("value").forGetter(AbilityDefinition.Parameter::value), ID.optionalFieldOf("profile").forGetter(AbilityDefinition.Parameter::profile)
        ).apply(i, AbilityDefinition.Parameter::new)), Set.of("value", "profile"));
        Codec<AbilityDefinition> ability = strict(RecordCodecBuilder.create(i -> i.group(
                ID.fieldOf("id").forGetter(AbilityDefinition::id), ID.fieldOf("slot").forGetter(AbilityDefinition::slot), cost.optionalFieldOf("cost").forGetter(AbilityDefinition::cost),
                conditions.optionalFieldOf("if", ALWAYS).forGetter(AbilityDefinition::condition), ID.listOf().xmap(Set::copyOf, v -> v.stream().sorted().toList()).optionalFieldOf("tags", Set.of()).forGetter(AbilityDefinition::tags),
                Codec.unboundedMap(Codec.STRING, parameter).optionalFieldOf("parameters", Map.of()).forGetter(AbilityDefinition::parameters), step.listOf().fieldOf("on_use").forGetter(AbilityDefinition::onUse)
        ).apply(i, AbilityDefinition::new)), Set.of("id", "slot", "cost", "if", "tags", "parameters", "on_use"));
        Codec<AmmoState.Reserve> weaponReserve = strict(RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("rounds").forGetter(AmmoState.Reserve::rounds), Codec.INT.fieldOf("capacity").forGetter(AmmoState.Reserve::capacity)
        ).apply(i, AmmoState.Reserve::new)), Set.of("rounds", "capacity"));
        Codec<java.util.Optional<AmmoState.Reserve>> reserves = Codec.either(Codec.STRING, weaponReserve).flatXmap(
                v -> v.map(s -> s.equals("unlimited") ? DataResult.success(java.util.Optional.empty()) : DataResult.error(() -> "Expected unlimited or finite reserves"),
                        r -> DataResult.success(java.util.Optional.of(r))),
                v -> DataResult.success(v.<Either<String, AmmoState.Reserve>>map(Either::right).orElseGet(() -> Either.left("unlimited"))));
        Codec<WeaponDefinition.Ammunition> weaponAmmo = strict(RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("capacity").forGetter(WeaponDefinition.Ammunition::capacity), Codec.INT.fieldOf("magazine").forGetter(WeaponDefinition.Ammunition::magazine),
                reserves.fieldOf("reserves").forGetter(WeaponDefinition.Ammunition::reserves), ID.optionalFieldOf("capacity_profile").forGetter(WeaponDefinition.Ammunition::capacityProfile)
        ).apply(i, WeaponDefinition.Ammunition::new)), Set.of("capacity", "magazine", "reserves", "capacity_profile"));
        Codec<WeaponDefinition.Reload> weaponReload = strict(RecordCodecBuilder.create(i -> i.group(
                values.fieldOf("value").forGetter(WeaponDefinition.Reload::value), ID.optionalFieldOf("profile").forGetter(WeaponDefinition.Reload::profile)
        ).apply(i, WeaponDefinition.Reload::new)), Set.of("value", "profile"));
        Codec<WeaponDefinition.Fire> weaponFire = strict(RecordCodecBuilder.create(i -> i.group(
                values.fieldOf("cost").forGetter(WeaponDefinition.Fire::cost), values.fieldOf("interval").forGetter(WeaponDefinition.Fire::interval),
                ID.optionalFieldOf("interval_profile").forGetter(WeaponDefinition.Fire::intervalProfile), conditions.optionalFieldOf("if", ALWAYS).forGetter(WeaponDefinition.Fire::condition),
                ID.listOf().xmap(Set::copyOf, v -> v.stream().sorted().toList()).optionalFieldOf("tags", Set.of()).forGetter(WeaponDefinition.Fire::tags),
                step.listOf().fieldOf("on_fire").forGetter(WeaponDefinition.Fire::onFire)
        ).apply(i, WeaponDefinition.Fire::new)), Set.of("cost", "interval", "interval_profile", "if", "tags", "on_fire"));
        Codec<WeaponDefinition> weapon = strict(RecordCodecBuilder.create(i -> i.group(
                ID.fieldOf("item").forGetter(WeaponDefinition::item), weaponAmmo.fieldOf("ammunition").forGetter(WeaponDefinition::ammunition),
                weaponReload.fieldOf("reload").forGetter(WeaponDefinition::reload), weaponFire.optionalFieldOf("fire").forGetter(WeaponDefinition::fire)
        ).apply(i, WeaponDefinition::new)), Set.of("item", "ammunition", "reload", "fire"));
        Codec<EffectProgram.Bundle> bundle = strict(RecordCodecBuilder.create(i -> i.group(
                ID.fieldOf("id").forGetter(EffectProgram.Bundle::id), enumeration(EffectProgram.Scope.class).optionalFieldOf("scope", EffectProgram.Scope.SOURCE).forGetter(EffectProgram.Bundle::scope),
                rule.listOf().optionalFieldOf("rules", List.of()).forGetter(EffectProgram.Bundle::rules), modifier.listOf().optionalFieldOf("modifiers", List.of()).forGetter(EffectProgram.Bundle::modifiers),
                recovery.listOf().optionalFieldOf("health_recovery", List.of()).forGetter(EffectProgram.Bundle::recovery),
                replacement.listOf().optionalFieldOf("ability_overrides", List.of()).forGetter(EffectProgram.Bundle::abilityOverrides)
        ).apply(i, EffectProgram.Bundle::new)), Set.of("id", "scope", "rules", "modifiers", "health_recovery", "ability_overrides"));
        Codec<EffectProgram.ShieldRecovery> shieldRecovery = strict(RecordCodecBuilder.create(i -> i.group(
                values.fieldOf("rate").forGetter(EffectProgram.ShieldRecovery::rate),
                conditions.optionalFieldOf("if", ALWAYS).forGetter(EffectProgram.ShieldRecovery::condition)
        ).apply(i, EffectProgram.ShieldRecovery::new)), Set.of("rate", "if"));
        Codec<EffectProgram.Shield> shield = strict(RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("capacity").forGetter(EffectProgram.Shield::capacity),
                values.optionalFieldOf("taken_multiplier", new Value.Constant(1, Unit.MULTIPLIER)).forGetter(EffectProgram.Shield::takenMultiplier),
                Codec.INT.optionalFieldOf("priority", 0).forGetter(EffectProgram.Shield::priority),
                values.optionalFieldOf("maximum").forGetter(EffectProgram.Shield::maximum),
                shieldRecovery.optionalFieldOf("recovery").forGetter(EffectProgram.Shield::recovery),
                ID.listOf().xmap(Set::copyOf, s -> s.stream().sorted().toList()).optionalFieldOf("excluded_attack_factors", Set.of()).forGetter(EffectProgram.Shield::excludedAttackFactors)
        ).apply(i, EffectProgram.Shield::new)), Set.of("capacity", "taken_multiplier", "priority", "maximum", "recovery", "excluded_attack_factors"));
        Codec<com.imdomestic.chorus.effect.combat.BuffConsumption.Policy> consumption = strict(RecordCodecBuilder.create(i -> i.group(
                enumeration(com.imdomestic.chorus.effect.combat.BuffConsumption.When.class).fieldOf("when").forGetter(com.imdomestic.chorus.effect.combat.BuffConsumption.Policy::when),
                Codec.INT.optionalFieldOf("stacks", 1).forGetter(com.imdomestic.chorus.effect.combat.BuffConsumption.Policy::stacks),
                conditions.optionalFieldOf("if", new Condition.Constant(true)).forGetter(com.imdomestic.chorus.effect.combat.BuffConsumption.Policy::condition)
        ).apply(i, com.imdomestic.chorus.effect.combat.BuffConsumption.Policy::new)), Set.of("when", "stacks", "if"));
        Codec<EffectProgram.Buff> buff = strict(RecordCodecBuilder.create(i -> i.group(
                BuffCodecs.DEFINITION.fieldOf("definition").forGetter(EffectProgram.Buff::definition), ID.optionalFieldOf("bundle", "").forGetter(EffectProgram.Buff::bundle),
                shield.optionalFieldOf("shield").forGetter(EffectProgram.Buff::shield), consumption.optionalFieldOf("consume_on_damage").forGetter(EffectProgram.Buff::consumeOnDamage)
        ).apply(i, EffectProgram.Buff::new)), Set.of("definition", "bundle", "shield", "consume_on_damage"));
        return strict(RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("version").forGetter(EffectProgram::version), buff.listOf().optionalFieldOf("buffs", List.of()).forGetter(EffectProgram::buffs),
                bundle.listOf().optionalFieldOf("bundles", List.of()).forGetter(EffectProgram::bundles), profiles.listOf().optionalFieldOf("profiles", List.of()).forGetter(EffectProgram::profiles),
                ID.optionalFieldOf("defense_profile").forGetter(EffectProgram::defenseProfile), RESOURCE.listOf().optionalFieldOf("resources", List.of()).forGetter(EffectProgram::resources),
                com.imdomestic.chorus.effect.equipment.EquipmentCodecs.SCHEMA.optionalFieldOf("equipment", com.imdomestic.chorus.effect.equipment.EquipmentSchema.EMPTY).forGetter(EffectProgram::equipment),
                ability.listOf().optionalFieldOf("abilities", List.of()).forGetter(EffectProgram::abilities),
                weapon.listOf().optionalFieldOf("weapons", List.of()).forGetter(EffectProgram::weapons)
        ).apply(i, EffectProgram::new)), Set.of("version", "buffs", "bundles", "profiles", "defense_profile", "resources", "equipment", "abilities", "weapons"));
    }
    public static final Codec<EffectProgram> PROGRAM = program(VALUE, CONDITION, ACTION, StatCodecs.PROFILE);
    public static Codec<CompiledEffects> compiled(Codec<EffectProgram> program) {
        return program.flatXmap(value -> safe(() -> new CompiledEffects(value)), value -> DataResult.success(value.program()));
    }
    public static final Codec<CompiledEffects> COMPILED = compiled(PROGRAM);
}
