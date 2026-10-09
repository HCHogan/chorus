package com.imdomestic.chorus.stat.codec;

import com.imdomestic.chorus.core.codec.TypeRegistry;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Minecraft-compatible data codecs, separated from the pure mathematical implementation. */
public final class StatCodecs {
    private StatCodecs() {}

    public static final Codec<Unit> UNIT = Codec.STRING.comapFlatMap(id -> safe(() -> new Unit(id.contains(":") ? id : "chorus:" + id)), Unit::id);
    private static final Codec<Double> FINITE = Codec.DOUBLE.validate(value ->
            Double.isFinite(value) ? DataResult.success(value) : DataResult.error(() -> "Number must be finite"));

    private static <T> DataResult<T> safe(Supplier<T> create) {
        try { return DataResult.success(create.get()); }
        catch (IllegalArgumentException e) { return DataResult.error(e::getMessage); }
    }
    private static <E extends Enum<E>> Codec<E> enumeration(Class<E> type) {
        return Codec.STRING.comapFlatMap(name -> safe(() -> Enum.valueOf(type, name.toUpperCase(Locale.ROOT))),
                value -> value.name().toLowerCase(Locale.ROOT));
    }

    private record FamilyData(FamilyPolicy.Kind kind, Map<String, Double> counts) {}
    public static final Codec<FamilyPolicy> FAMILY = RecordCodecBuilder.<FamilyData>create(i -> i.group(
            enumeration(FamilyPolicy.Kind.class).fieldOf("kind").forGetter(FamilyData::kind),
            Codec.unboundedMap(Codec.STRING, FINITE).optionalFieldOf("counts", Map.of()).forGetter(FamilyData::counts)
    ).apply(i, FamilyData::new)).flatXmap(data -> safe(() -> {
        var counts = new TreeMap<Integer, Double>();
        for (var entry : data.counts().entrySet()) {
            int count = Integer.parseInt(entry.getKey());
            if (counts.putIfAbsent(count, entry.getValue()) != null) throw new IllegalArgumentException("Duplicate family count: " + count);
        }
        return new FamilyPolicy(data.kind(), counts);
    }),
            policy -> DataResult.success(new FamilyData(policy.kind(), policy.countValues().entrySet().stream()
                    .collect(Collectors.toMap(e -> Integer.toString(e.getKey()), Map.Entry::getValue)))));

    private record GroupData(String name, Reduction reduction, Map<String, FamilyPolicy> families, List<NumericGroup> children) {}
    public static final Codec<NumericGroup> GROUP = Codec.recursive("ChorusNumericGroup", self -> RecordCodecBuilder.<GroupData>create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(GroupData::name),
            enumeration(Reduction.class).fieldOf("reduction").forGetter(GroupData::reduction),
            Codec.unboundedMap(Codec.STRING, FAMILY).optionalFieldOf("families", Map.of()).forGetter(GroupData::families),
            self.listOf().optionalFieldOf("children", List.of()).forGetter(GroupData::children)
    ).apply(i, GroupData::new)).flatXmap(data -> safe(() -> new NumericGroup(data.name(), data.reduction(), data.families(), data.children())),
            group -> DataResult.success(new GroupData(group.name(), group.reduction(), group.families(), group.children()))));

    private record Point(double input, double output) {}
    private static final Codec<Point> POINT = com.imdomestic.chorus.core.codec.DataCodecs.strict(RecordCodecBuilder.create(i -> i.group(
            FINITE.fieldOf("input").forGetter(Point::input), FINITE.fieldOf("output").forGetter(Point::output)
    ).apply(i, Point::new)), java.util.Set.of("input", "output"));
    private record TableData(List<Point> points, Curve.Interpolation interpolation, Curve.Boundary boundary) {}
    private static final MapCodec<Curve.Table> TABLE = RecordCodecBuilder.<TableData>mapCodec(i -> i.group(
            POINT.listOf().fieldOf("points").forGetter(TableData::points),
            enumeration(Curve.Interpolation.class).fieldOf("interpolation").forGetter(TableData::interpolation),
            enumeration(Curve.Boundary.class).fieldOf("boundary").forGetter(TableData::boundary)
    ).apply(i, TableData::new)).flatXmap(data -> safe(() -> {
        var points = new TreeMap<Double, Double>();
        for (var point : data.points()) if (points.putIfAbsent(point.input(), point.output()) != null) {
            throw new IllegalArgumentException("Duplicate curve point: " + point.input());
        }
        return new Curve.Table(points, data.interpolation(), data.boundary());
    }), curve -> DataResult.success(new TableData(curve.points().entrySet().stream().map(e -> new Point(e.getKey(), e.getValue())).toList(),
            curve.interpolation(), curve.boundary())));

    private record PolynomialData(List<Double> coefficients, double minimum, double maximum, Curve.Boundary boundary) {}
    private static final MapCodec<Curve.Polynomial> POLYNOMIAL = RecordCodecBuilder.<PolynomialData>mapCodec(i -> i.group(
            FINITE.listOf().fieldOf("coefficients").forGetter(PolynomialData::coefficients),
            FINITE.fieldOf("minimum").forGetter(PolynomialData::minimum),
            FINITE.fieldOf("maximum").forGetter(PolynomialData::maximum),
            enumeration(Curve.Boundary.class).fieldOf("boundary").forGetter(PolynomialData::boundary)
    ).apply(i, PolynomialData::new)).flatXmap(data -> safe(() -> new Curve.Polynomial(data.coefficients(), data.minimum(), data.maximum(), data.boundary())),
            curve -> DataResult.success(new PolynomialData(curve.coefficients(), curve.minimum(), curve.maximum(), curve.boundary())));

    public static TypeRegistry<Curve> curveTypes() {
        return new TypeRegistry<Curve>().register("chorus:table", Curve.Table.class, TABLE)
                .register("chorus:polynomial", Curve.Polynomial.class, POLYNOMIAL);
    }
    public static final Codec<Curve> CURVE = curveTypes().build();

    private record ApplyData(String id, NumericContribution.Operation operation, NumericGroup group, String percentOf, String factor) {}
    private static final MapCodec<CalculationStep.Apply> APPLY = RecordCodecBuilder.<ApplyData>mapCodec(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(ApplyData::id),
            enumeration(NumericContribution.Operation.class).fieldOf("operation").forGetter(ApplyData::operation),
            GROUP.fieldOf("group").forGetter(ApplyData::group),
            Codec.STRING.optionalFieldOf("percent_of", "").forGetter(ApplyData::percentOf),
            Codec.STRING.optionalFieldOf("factor", "").forGetter(ApplyData::factor)
    ).apply(i, ApplyData::new)).flatXmap(data -> safe(() -> new CalculationStep.Apply(data.id(), data.operation(), data.group(), data.percentOf(), data.factor())),
            step -> DataResult.success(new ApplyData(step.id(), step.operation(), step.group(), step.percentOf(), step.factor())));

    private record ClampData(String id, double minimum, double maximum) {}
    private static final MapCodec<CalculationStep.Clamp> CLAMP = RecordCodecBuilder.<ClampData>mapCodec(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(ClampData::id), FINITE.fieldOf("minimum").forGetter(ClampData::minimum),
            FINITE.fieldOf("maximum").forGetter(ClampData::maximum)
    ).apply(i, ClampData::new)).flatXmap(data -> safe(() -> new CalculationStep.Clamp(data.id(), data.minimum(), data.maximum())),
            step -> DataResult.success(new ClampData(step.id(), step.minimum(), step.maximum())));

    private static MapCodec<CalculationStep.Transform> transform(Codec<Curve> curves) {
        return RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(CalculationStep.Transform::id),
                curves.fieldOf("curve").forGetter(CalculationStep.Transform::curve),
                UNIT.fieldOf("output_unit").forGetter(CalculationStep.Transform::outputUnit)
        ).apply(i, CalculationStep.Transform::new));
    }
    private static final MapCodec<CalculationStep.Round> ROUND = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(CalculationStep.Round::id),
            enumeration(CalculationStep.Rounding.class).fieldOf("rounding").forGetter(CalculationStep.Round::rounding)
    ).apply(i, CalculationStep.Round::new));

    private record ProfileData(String id, String version, Unit inputUnit, List<CalculationStep> steps) {}
    public static Codec<CalculationProfile> profile(Codec<Curve> curves) {
        Codec<CalculationStep> steps = new TypeRegistry<CalculationStep>()
                .register("chorus:apply", CalculationStep.Apply.class, APPLY)
                .register("chorus:clamp", CalculationStep.Clamp.class, CLAMP)
                .register("chorus:curve", CalculationStep.Transform.class, transform(curves))
                .register("chorus:round", CalculationStep.Round.class, ROUND).build();
        return RecordCodecBuilder.<ProfileData>create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(ProfileData::id),
                Codec.STRING.fieldOf("version").forGetter(ProfileData::version),
                UNIT.fieldOf("input_unit").forGetter(ProfileData::inputUnit),
                steps.listOf().fieldOf("steps").forGetter(ProfileData::steps)
        ).apply(i, ProfileData::new)).flatXmap(data -> safe(() -> new CalculationProfile(data.id(), data.version(), data.inputUnit(), data.steps())),
                profile -> DataResult.success(new ProfileData(profile.id(), profile.version(), profile.inputUnit(), profile.steps())));
    }
    public static final Codec<CalculationProfile> PROFILE = profile(CURVE);
}
