package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.rule.TimelineEngine;
import com.imdomestic.chorus.stat.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Function;

/** Pinned compiled catalogue. Engine code contains no Destiny identifiers or perk-specific branches. */
public final class CompiledEffects implements RuleEngine.RuleResolver<EffectState> {
    private final EffectProgram program;
    private final Map<String, BuffDefinition> buffs;
    private final Map<String, EffectProgram.Bundle> bundles;
    private final Map<String, CalculationProfile> profiles;
    private final Map<String, ResourceDefinition> resources;
    private final Map<String, EffectProgram.Shield> shields;
    private final CompiledEquipment equipment;
    private final CompiledWeapons weapons;
    private final Map<String, AbilityDefinition> abilities;
    private final Map<String, RuleEngine.EventRule<EffectState>> abilityRules;
    private final Map<String, RuleEngine.EventRule<EffectState>> fireRules;
    private final Map<String, List<RuleEngine.EventRule<EffectState>>> sourceRules;
    private final Map<String, String> buffBundles;
    private final BuffRules<EffectState> buffRules;
    private final List<RuleEngine.EventRule<EffectState>> definitions;
    private final java.util.Set<String> continuationIds;

    /** Link decoded fragments, then validate once with all cross-fragment references available. No overrides or version rewriting. */
    public static CompiledEffects link(List<EffectProgram> fragments) {
        fragments = List.copyOf(fragments);
        if (fragments.isEmpty()) throw new IllegalArgumentException("Cannot link an empty effect catalogue");
        String version = fragments.getFirst().version();
        var buffs = new ArrayList<EffectProgram.Buff>(); var bundles = new ArrayList<EffectProgram.Bundle>();
        var abilities = new ArrayList<AbilityDefinition>();
        var weapons = new ArrayList<WeaponDefinition>();
        var profiles = new ArrayList<CalculationProfile>(); var resources = new ArrayList<ResourceDefinition>();
        var slots = new ArrayList<EquipmentSchema.Slot>(); var items = new ArrayList<EquipmentSchema.Item>(); var limits = new ArrayList<EquipmentSchema.Limit>();
        Optional<String> defense = Optional.empty(), presentation = Optional.empty();
        for (var fragment : fragments) {
            if (!version.equals(fragment.version())) throw new IllegalArgumentException("Mixed fragment ruleset versions");
            if (fragment.defenseProfile().isPresent()) {
                if (defense.isPresent()) throw new IllegalArgumentException("Multiple fragments select a defense profile");
                defense = fragment.defenseProfile();
            }
            buffs.addAll(fragment.buffs()); bundles.addAll(fragment.bundles());
            abilities.addAll(fragment.abilities());
            weapons.addAll(fragment.weapons());
            profiles.addAll(fragment.profiles()); resources.addAll(fragment.resources());
            slots.addAll(fragment.equipment().slots()); items.addAll(fragment.equipment().items()); limits.addAll(fragment.equipment().limits());
            if (fragment.equipment().presentation().isPresent()) {
                if (presentation.isPresent() && !presentation.equals(fragment.equipment().presentation())) throw new IllegalArgumentException("Conflicting equipment presentations");
                presentation = fragment.equipment().presentation();
            }
        }
        return new CompiledEffects(new EffectProgram(version, buffs, bundles, profiles, defense, resources, new EquipmentSchema(slots, items, limits, presentation), abilities, weapons));
    }

    public CompiledEffects(EffectProgram program) {
        this.program = program;
        if (program.version().isBlank()) throw new IllegalArgumentException("Missing program version");
        this.buffs = index(program.buffs().stream().map(EffectProgram.Buff::definition).toList(), BuffDefinition::id);
        this.bundles = index(program.bundles(), EffectProgram.Bundle::id);
        this.equipment = new CompiledEquipment(program.equipment(), id -> {
            if (bundle(id).scope() != EffectProgram.Scope.SOURCE) throw new IllegalArgumentException("Equipment requires a source-scoped bundle: " + id);
        });
        this.profiles = index(program.profiles(), CalculationProfile::id);
        this.resources = index(program.resources(), ResourceDefinition::id);
        this.abilities = index(program.abilities(), AbilityDefinition::id);
        for (var resource : resources.values()) resource.rateProfile().ifPresent(id -> {
            var profile = profiles.get(id); if (profile == null) throw new IllegalArgumentException("Unknown resource rate profile: " + id);
            Validation.same(profile.inputUnit(), Unit.CHARGE_PER_SECOND); Validation.same(profile.outputUnit(), Unit.CHARGE_PER_SECOND);
        });
        program.defenseProfile().ifPresent(id -> new Validation(buffs, Map.of(), false, profiles).damageProfile(id));
        var shieldDefinitions = new HashMap<String, EffectProgram.Shield>();
        program.buffs().forEach(buff -> buff.shield().ifPresent(shield -> shieldDefinitions.put(buff.definition().id(), shield)));
        this.shields = Map.copyOf(shieldDefinitions);
        this.weapons = new CompiledWeapons(program.weapons(), equipment, new Validation(buffs, Map.of(), false, profiles, resources, shields), profiles);
        for (var buff : program.buffs()) buff.shield().ifPresent(shield -> {
            Validation.same(buff.definition().components().number(shield.capacity()).unit(), Unit.DAMAGE);
            Numbers.nonnegative(buff.definition().components().number(shield.capacity()).value(), "initial shield capacity");
            Validation.same(shield.takenMultiplier().unit(new Validation(buffs, Map.of(), true, profiles, resources)), Unit.MULTIPLIER);
            if (shield.takenMultiplier() instanceof Value.Constant value) Numbers.nonnegative(value.value(), "shield multiplier");
            shield.maximum().ifPresent(value -> {
                Validation.same(value.unit(new Validation(buffs, Map.of(), true, profiles, resources, shields)), Unit.DAMAGE);
                if (value instanceof Value.Constant constant) Numbers.nonnegative(constant.value(), "shield maximum");
            });
            shield.recovery().ifPresent(recovery -> {
                var validation = new Validation(buffs, Map.of(), true, profiles, resources, shields);
                recovery.condition().validate(validation);
                Validation.same(recovery.rate().unit(validation), Unit.DAMAGE_PER_SECOND);
                if (recovery.rate() instanceof Value.Constant constant) Numbers.nonnegative(constant.value(), "shield recovery rate");
            });
        });
        buffs.values().forEach(definition -> version(definition.version())); profiles.values().forEach(profile -> version(profile.version()));
        var continuations = new ArrayList<RuleEngine.EventRule<EffectState>>();
        var compiled = new LinkedHashMap<String, List<RuleEngine.EventRule<EffectState>>>();
        for (var bundle : program.bundles()) {
            var ids = new HashSet<String>();
            var rules = new ArrayList<RuleEngine.EventRule<EffectState>>();
            for (var rule : bundle.rules()) {
                localId(rule.id()); namespaced(rule.on());
                if (rule.on().startsWith("chorus:internal/")) throw new IllegalArgumentException("Reserved internal event");
                if (!ids.add(rule.id())) throw new IllegalArgumentException("Duplicate rule: " + rule.id());
                rules.add(compile(bundle, rule, continuations));
            }
            ids.clear();
            for (var modifier : bundle.modifiers()) {
                localId(modifier.id()); if (!ids.add(modifier.id())) throw new IllegalArgumentException("Duplicate modifier: " + modifier.id());
                validateModifier(bundle, modifier);
            }
            ids.clear();
            for (var recovery : bundle.recovery()) {
                localId(recovery.id()); namespaced(recovery.channel());
                if (!ids.add(recovery.id())) throw new IllegalArgumentException("Duplicate recovery: " + recovery.id());
                var validation = validation(bundle, Map.of()); recovery.condition().validate(validation);
                Validation.same(recovery.rate().unit(validation), Unit.DAMAGE_PER_SECOND);
                if (recovery.rate() instanceof Value.Constant constant) Numbers.nonnegative(constant.value(), "health recovery rate");
                recovery.tags().forEach(CompiledEffects::namespaced);
            }
            ids.clear();
            for (var replacement : bundle.abilityOverrides()) {
                if (!ids.add(replacement.id())) throw new IllegalArgumentException("Duplicate ability replacement: " + replacement.id());
                validateSelection(replacement.slot(), replacement.replaceWith()); replacement.ability().ifPresent(id -> validateSelection(replacement.slot(), id));
                replacement.condition().validate(validation(bundle, Map.of()));
            }
            compiled.put(bundle.id(), List.copyOf(rules));
        }
        var sourceRules = new LinkedHashMap<String, List<RuleEngine.EventRule<EffectState>>>();
        for (var bundle : program.bundles()) if (bundle.scope() == EffectProgram.Scope.SOURCE) sourceRules.put(bundle.id(), compiled.get(bundle.id()));
        this.sourceRules = Map.copyOf(sourceRules);
        var attached = new LinkedHashMap<BuffDefinition, List<RuleEngine.EventRule<EffectState>>>();
        var buffBundles = new HashMap<String, String>();
        for (var buff : program.buffs()) if (!buff.bundle().isEmpty()) {
            var bundle = bundle(buff.bundle());
            if (bundle.scope() != EffectProgram.Scope.BUFF) throw new IllegalArgumentException("Buff needs a buff-scoped bundle: " + buff.bundle());
            // Reusing a bundle gives each definition its own pinned rule identity.
            var copies = compiled.get(bundle.id()).stream().map(rule -> new RuleEngine.EventRule<>(
                    buff.definition().id() + "/attached/" + rule.definition(), rule.eventType(), rule.condition(), rule.actions())).toList();
            attached.put(buff.definition(), copies); buffBundles.put(buff.definition().id(), bundle.id());
        }
        this.buffBundles = Map.copyOf(buffBundles);
        this.buffRules = new BuffRules<>(attached, EffectState::buffs);
        var definitions = new ArrayList<RuleEngine.EventRule<EffectState>>();
        sourceRules.values().forEach(definitions::addAll); definitions.addAll(buffRules.definitions());
        definitions.add(weaponRule(true)); definitions.add(weaponRule(false));
        definitions.addAll(Recovery.definitions());
        definitions.add(new RuleEngine.EventRule<>(SourceChange.EVENT, SourceChange.EVENT, (_, _) -> true,
                List.of(new RuleEngine.Instruction<>((state, context) -> {
                    var change = (SourceChange) context.event().signal().payload();
                    validateSourceChange(change);
                    return change.apply(state);
                }, ""))));
        definitions.add(new RuleEngine.EventRule<>(EquipmentChange.EVENT, EquipmentChange.EVENT, (_, _) -> true,
                List.of(new RuleEngine.Instruction<>((state, context) -> changeEquipment(state, (EquipmentChange) context.event().signal().payload()), ""))));
        definitions.add(new RuleEngine.EventRule<>(WeaponReload.REQUEST, WeaponReload.REQUEST, (_, _) -> true,
                List.of(new RuleEngine.Instruction<>((state, context) -> reload(state, (WeaponReload.Request) context.event().signal().payload()), ""))));
        definitions.add(new RuleEngine.EventRule<>(WeaponReload.DUE, WeaponReload.DUE, (state, context) -> {
                    var plan = (WeaponReload.Plan) context.event().signal().payload(); return plan.equals(state.reloads().get(plan.holder()));
                }, List.of(new RuleEngine.Instruction<>((_, context) -> new RuleEngine.Await<EffectState>(new WeaponReload.Verify((WeaponReload.Plan) context.event().signal().payload())), "reload_host"),
                        new RuleEngine.Instruction<>((state, context) -> weapons.finish(state, (WeaponReload.Plan) context.event().signal().payload(),
                                (WeaponReload.Verified) context.bindings().get("reload_host"), this), ""))));
        definitions.add(new RuleEngine.EventRule<>(SourceBatch.EVENT, SourceBatch.EVENT, (_, _) -> true,
                List.of(new RuleEngine.Instruction<>((state, context) -> {
                    var batch = (SourceBatch) context.event().signal().payload();
                    validateSources(batch); return batch.apply(state);
                }, ""))));
        definitions.add(new RuleEngine.EventRule<>(WeaponFire.REQUEST, WeaponFire.REQUEST, (_, _) -> true,
                List.of(new RuleEngine.Instruction<>((state, context) -> fire(state, (WeaponFire.Request) context.event().signal().payload()), ""))));
        var fireRules = new LinkedHashMap<String, RuleEngine.EventRule<EffectState>>();
        for (var weapon : program.weapons()) weapon.fire().ifPresent(fire -> {
            var scope = new EffectProgram.Bundle(weapon.item(), EffectProgram.Scope.SOURCE, List.of(), List.of());
            validateInstantSteps(fire.onFire()); validatePayments(fire.onFire(), new HashSet<>());
            String id = "chorus:internal/fire/" + weapon.item();
            var actions = compileSteps(scope, fire.onFire(), Map.of(), Map.of(), "", id, continuations);
            var rule = new RuleEngine.EventRule<EffectState>(id, WeaponFire.ACCEPTED, (_, _) -> true, actions);
            fireRules.put(weapon.item(), rule); definitions.add(rule);
        });
        this.fireRules = Map.copyOf(fireRules);
        var abilityRules = new LinkedHashMap<String, RuleEngine.EventRule<EffectState>>();
        for (var ability : abilities.values()) {
            var scope = new EffectProgram.Bundle(ability.id(), EffectProgram.Scope.SOURCE, List.of(), List.of());
            var validation = validation(scope, Map.of()); ability.condition().validate(validation);
            ability.cost().ifPresent(cost -> {
                validation.resource(cost.resource()); Validation.same(cost.amount().unit(validation), Unit.CHARGE);
                if (cost.amount() instanceof Value.Constant c) Numbers.nonnegative(c.value(), "ability cost");
                validateAbilityProfile(cost.profile(), Unit.CHARGE);
            });
            ability.parameters().values().forEach(parameter -> validateAbilityProfile(parameter.profile(), parameter.value().unit(validation)));
            validateInstantSteps(ability.onUse()); validatePayments(ability.onUse(), new HashSet<>());
            var actions = new ArrayList<RuleEngine.Instruction<EffectState>>();
            var resultShapes = ability.cost().isPresent() ? Map.of("cast_cost", ResultShape.RESOURCE_SPEND) : Map.<String, ResultShape>of();
            var resultSlots = ability.cost().isPresent() ? Map.of("cast_cost", "cast_cost") : Map.<String, String>of();
            if (ability.cost().isPresent()) actions.add(new RuleEngine.Instruction<>((state, context) ->
                    new RuleEngine.Local<>(state, ((AbilityUse.Used) context.event().signal().payload()).receipt().cost().orElseThrow(), List.of()), "cast_cost"));
            String id = "chorus:internal/ability/" + ability.id();
            actions.addAll(compileSteps(scope, ability.onUse(), resultShapes, resultSlots, "", id, continuations));
            var rule = new RuleEngine.EventRule<EffectState>(id, "chorus:ability_used", (_, _) -> true, actions);
            abilityRules.put(ability.id(), rule); definitions.add(rule);
        }
        this.abilityRules = Map.copyOf(abilityRules);
        definitions.add(new RuleEngine.EventRule<>(AbilityChange.EVENT, AbilityChange.EVENT, (_, _) -> true,
                List.of(new RuleEngine.Instruction<>((state, context) -> ((AbilityChange) context.event().signal().payload()).apply(state, this), ""))));
        definitions.add(new RuleEngine.EventRule<>(AbilityUse.EVENT, AbilityUse.EVENT, (_, _) -> true,
                List.of(new RuleEngine.Instruction<>((state, context) -> useAbility(state, (AbilityUse.Request) context.event().signal().payload()), ""))));
        definitions.addAll(continuations);
        this.continuationIds = continuations.stream().map(RuleEngine.EventRule::definition).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.definitions = List.copyOf(definitions);
    }
    private void validateAbilityProfile(Optional<String> id, Unit unit) {
        id.ifPresent(key -> { var profile = profiles.get(key); if (profile == null) throw new IllegalArgumentException("Unknown ability profile: " + key);
            Validation.same(profile.inputUnit(), unit); Validation.same(profile.outputUnit(), unit); });
    }
    private static void validateInstantSteps(List<EffectProgram.Step> steps) {
        for (var step : steps) switch (step) {
            case EffectProgram.Instruction instruction -> {
                if (instruction.action() instanceof Action.Schedule || instruction.action() instanceof Action.CancelTimer)
                    throw new IllegalArgumentException("Instant invocation actions need detached after or a durable buff/source for named timers");
            }
            case EffectProgram.Branch branch -> { validateInstantSteps(branch.then()); validateInstantSteps(branch.otherwise()); }
            case EffectProgram.ForEach loop -> validateInstantSteps(loop.body());
            case EffectProgram.Projectile projectile -> validateInstantSteps(projectile.body());
            case EffectProgram.After after -> {
                if (after.lifetime() != EffectContinuations.Lifetime.DETACHED) throw new IllegalArgumentException("Instant invocation continuation must explicitly be detached");
                validateInstantSteps(after.body());
            }
        }
    }
    private AbilityDefinition validateSelection(String slot, String id) {
        var definition = abilities.get(id);
        if (definition == null || !definition.slot().equals(slot)) throw new IllegalArgumentException("Unknown ability or incompatible slot: " + slot + " -> " + id);
        return definition;
    }
    public RuleEngine.Local<EffectState> changeAbilities(EffectState state, AbilityChange change) {
        if (!state.abilities().getOrDefault(change.holder(), AbilityLoadout.EMPTY).equals(change.before())) throw new IllegalStateException("Stale ability selection");
        change.before().slots().forEach(this::validateSelection); change.after().slots().forEach(this::validateSelection);
        if (change.before().equals(change.after())) return new RuleEngine.Local<>(state, RuleEngine.Empty.INSTANCE, List.of());
        var updated = state.withAbilities(change.holder(), change.after()); var facts = new ArrayList<RuleEngine.Signal>();
        var origin = new BuffInstance.Origin(change.holder(), "chorus:abilities", "", "");
        // A slot may temporarily use another declared pool. Initialize each eligible pool once, never on every cast or replacement.
        var needed = new java.util.TreeSet<String>();
        abilities.values().stream().filter(ability -> change.after().slots().containsKey(ability.slot())).forEach(ability -> ability.cost().ifPresent(cost -> needed.add(cost.resource())));
        for (String id : needed) {
            var definition = resources.get(id); var key = new ResourceState.Key(change.holder(), id); var existing = updated.resources().get(key);
            if (existing != null) { definition.validate(existing); continue; }
            var account = definition.initialize(change.holder(), state.buffs().timeMicros()); updated = updated.withResource(account);
            facts.add(new RuleEngine.Signal("chorus:resource_initialized", ResourceFacts.change(new ResourceState(key, 0, account.capacity(), account.timeMicros()), account, origin, "initialize")));
        }
        facts.add(new RuleEngine.Signal("chorus:abilities_changed", new EffectEvent(change.holder(), change.holder(), origin, java.util.Set.of(), Map.of())));
        return new RuleEngine.Local<>(updated, RuleEngine.Empty.INSTANCE, facts);
    }
    private record ReplacementSite(AbilityDefinition.Replacement replacement, RuleEngine.Payload scope, String instance) {}
    private List<ReplacementSite> abilityReplacements(EffectState state, String holder) {
        var sites = new ArrayList<ReplacementSite>();
        for (var source : state.sources().values()) if (source.holder().equals(holder))
            for (var replacement : bundle(source.bundle()).abilityOverrides()) sites.add(new ReplacementSite(replacement, source, sourceId(source)));
        for (var buff : state.buffs().instances().values()) if (buff.key().holder().equals(holder) && buff.pausedAt().isEmpty() && buff.activeCount(state.buffs().timeMicros()) > 0) {
            String id = buffBundles.get(buff.definition().id()); if (id == null) continue;
            for (var replacement : bundle(id).abilityOverrides()) sites.add(new ReplacementSite(replacement, new BuffRules.Scope(buff, false), "buff/" + buff.generation()));
        }
        return sites.stream().sorted(java.util.Comparator.comparingInt(site -> site.replacement().priority())).toList();
    }
    private static EffectEvent abilityEvent(AbilityUse.Request request, String base, AbilityDefinition definition) {
        var tags = new HashSet<>(request.input().tags()); tags.addAll(definition.tags());
        var origin = new BuffInstance.Origin(request.holder(), "cast/" + request.cast(), "", definition.id(), java.util.Set.copyOf(tags));
        var refs = new HashMap<>(request.input().references()); refs.put("ability", definition.id()); refs.put("base_ability", base); refs.put("ability_slot", request.slot()); refs.put("cast", request.cast());
        return new EffectEvent(request.holder(), request.input().victim(), origin, tags, request.input().numbers(), request.input().flags(), refs, request.input().impact());
    }
    private RuleEngine.Local<EffectState> rejectedAbility(EffectState state, AbilityUse.Request request, String base, String resolved, AbilityUse.Outcome outcome, Optional<Resources.SpendResult> cost) {
        return new RuleEngine.Local<>(state, new AbilityUse.Receipt(request.cast(), request.slot(), base, resolved, outcome, cost), List.of());
    }
    public RuleEngine.Local<EffectState> useAbility(EffectState state, AbilityUse.Request request) {
        settled(state);
        String base = state.abilities().getOrDefault(request.holder(), AbilityLoadout.EMPTY).slots().get(request.slot());
        if (base == null) return rejectedAbility(state, request, "", "", AbilityUse.Outcome.EMPTY_SLOT, Optional.empty());
        var selected = validateSelection(request.slot(), base); var sites = abilityReplacements(state, request.holder());
        for (int i = 0; i < sites.size();) {
            int priority = sites.get(i).replacement().priority(); var matches = new HashSet<String>();
            var query = queryEvent(state, abilityEvent(request, base, selected));
            while (i < sites.size() && sites.get(i).replacement().priority() == priority) {
                var site = sites.get(i++); var replacement = site.replacement();
                if (!replacement.slot().equals(request.slot()) || replacement.ability().isPresent() && !replacement.ability().orElseThrow().equals(selected.id())) continue;
                if (site.scope() instanceof BuffRules.Scope scope && !scope.snapshot().affects("", selected.id())) continue;
                if (replacement.condition().test(evaluation(state, new RuleEngine.Context(query, site.instance(), site.scope(), Map.of()), Map.of()))) matches.add(replacement.replaceWith());
            }
            if (matches.size() > 1) return rejectedAbility(state, request, base, selected.id(), AbilityUse.Outcome.CONFLICT, Optional.empty());
            if (!matches.isEmpty()) selected = validateSelection(request.slot(), matches.iterator().next());
        }
        var query = abilityEvent(request, base, selected); var scope = new AbilityUse.Scope(query);
        var evaluation = evaluation(state, new RuleEngine.Context(queryEvent(state, query), "cast/" + request.cast(), scope, Map.of()), Map.of());
        if (!selected.condition().test(evaluation)) return rejectedAbility(state, request, base, selected.id(), AbilityUse.Outcome.CONDITION, Optional.empty());
        var numbers = new HashMap<>(query.numbers());
        for (var entry : selected.parameters().entrySet()) {
            var parameter = entry.getValue(); var value = parameter.value().evaluate(evaluation);
            if (parameter.profile().isPresent()) value = calculate(state, request.holder(), query, parameter.profile().orElseThrow(), value, List.of()).output();
            numbers.put("param." + entry.getKey(), value);
        }
        var updated = state; Optional<Resources.SpendResult> payment = Optional.empty(); var facts = new ArrayList<RuleEngine.Signal>();
        if (selected.cost().isPresent()) {
            var cost = selected.cost().orElseThrow(); var account = evaluation.resource(cost.resource(), Evaluation.Target.SELF);
            var amount = cost.amount().evaluate(evaluation);
            if (cost.profile().isPresent()) amount = calculate(state, request.holder(), query, cost.profile().orElseThrow(), amount, List.of()).output();
            Validation.same(amount.unit(), Unit.CHARGE); Numbers.nonnegative(amount.value(), "ability cost");
            var spent = Resources.spend(account, "cast/" + request.cast(), amount.value()); payment = Optional.of(spent);
            if (!spent.succeeded()) return rejectedAbility(state, request, base, selected.id(), AbilityUse.Outcome.INSUFFICIENT_ENERGY, payment);
            updated = state.withResource(spent.after());
            if (spent.receipt().paid() > 0) {
                var fact = ResourceFacts.change(account, spent.after(), query.source(), "spend");
                facts.add(new RuleEngine.Signal("chorus:resource_spent", fact)); facts.add(new RuleEngine.Signal("chorus:resource_changed", fact));
            }
        }
        double paid = payment.map(value -> value.receipt().paid()).orElse(0.0); numbers.put("paid", new Measure(paid, Unit.CHARGE));
        var flags = new HashMap<>(query.flags()); flags.put("free", paid == 0);
        var usedEvent = new EffectEvent(query.actor(), query.victim(), query.source(), query.tags(), numbers, flags, query.references(), query.impact());
        var receipt = new AbilityUse.Receipt(request.cast(), request.slot(), base, selected.id(), AbilityUse.Outcome.ACCEPTED, payment);
        facts.add(new RuleEngine.Signal("chorus:ability_used", new AbilityUse.Used(usedEvent, selected, receipt)));
        return new RuleEngine.Local<>(updated, receipt, facts);
    }
    public EffectProgram program() { return program; }
    public CompiledEquipment equipment() { return equipment; }
    public RuleEngine.Local<EffectState> changeEquipment(EffectState state, EquipmentChange change) { return weapons.equip(state, change, this); }
    public RuleEngine.Local<EffectState> reload(EffectState state, WeaponReload.Request request) { settled(state); return weapons.begin(state, request, this); }
    public RuleEngine.Local<EffectState> fire(EffectState state, WeaponFire.Request request) { settled(state); return weapons.fire(state, request, this); }
    public void validateSource(EffectSource source) {
        if (bundle(source.bundle()).scope() != EffectProgram.Scope.SOURCE) throw new IllegalArgumentException("Equipped source references a buff-only bundle");
    }
    public void validateSources(SourceBatch batch) { batch.edits().forEach(edit -> { unmanagedSource(edit.instance()); edit.after().ifPresent(this::validateSource); }); }
    public void validateSourceChange(SourceChange change) { unmanagedSource(change.instance()); change.replacement().ifPresent(this::validateSource); }
    private static void unmanagedSource(String instance) { if (instance.startsWith("equipment/")) throw new IllegalArgumentException("Equipment sources must change through EquipmentChange"); }
    /** Attack scaling uses only the attack owner's modifiers; target debuffs belong in the defense profile. */
    public Optional<CalculationProfile.Result> outgoing(EffectState state, DamageCommand command, double input) {
        if (command.snapshot().isEmpty()) return damageCalculation(state, command, command.source().owner(), command.scalingProfile(), input);
        settled(state); Numbers.nonnegative(input, "damage profile input");
        var snapshot = command.snapshot().orElseThrow(); snapshot.validate(command);
        var event = queryEvent(state, damageQuery(command, input));
        var result = calculateCaptured(state, command.source().owner(), event, snapshot.profile(), new Measure(input, Unit.DAMAGE), snapshot.resolve(state, event, this));
        Numbers.nonnegative(result.output().value(), "damage profile output");
        return Optional.of(result);
    }
    private CalculationProfile.Result calculateCaptured(EffectState state, String holder, RuleEngine.Event event,
            CalculationProfile profile, Measure base, List<NumericContribution> captured) {
        var contributions = new ArrayList<>(captured);
        visitModifiers(state, holder, event, profile.id(), site -> {
            if (site.modifier().evaluate() != EffectProgram.Evaluate.ON_HIT) return;
            var current = profiles.get(site.modifier().profile());
            if (!current.inputUnit().equals(profile.inputUnit()) || !current.steps().equals(profile.steps())) {
                throw new IllegalArgumentException("Live on_hit modifier disagrees with the captured profile layout");
            }
            if (site.modifier().condition().test(site.evaluation())) contributions.add(site.numeric(site.modifier().value().evaluate(site.evaluation())));
        });
        return profile.calculate(base, contributions);
    }
    /** Global target-side profile runs after native attack gates and before per-layer shield multipliers. */
    public Optional<CalculationProfile.Result> defense(EffectState state, DamageCommand command, double input) {
        return damageCalculation(state, command, command.target(), program.defenseProfile(), input);
    }
    private Optional<CalculationProfile.Result> damageCalculation(EffectState state, DamageCommand command,
            String holder, Optional<String> profile, double input) {
        Numbers.nonnegative(input, "damage profile input");
        return profile.map(id -> {
            new Validation(buffs, Map.of(), false, profiles).damageProfile(id);
            var query = damageQuery(command, input);
            var result = calculate(state, holder, query, id, new Measure(input, Unit.DAMAGE), List.of());
            Numbers.nonnegative(result.output().value(), "damage profile output");
            return result;
        });
    }
    public TimelineEngine<EffectState> engine(EffectClock clock, int budget) {
        var configured = clock.withRates((state, account) -> resources.containsKey(account.key().resource())
                ? resourceRate(state, account) : clock.resourceRate(state, account)).withRecovery(this::recoveryOffers).withShieldRecovery(this::shieldRecoveryOffers);
        return new TimelineEngine<>(program.version(), definitions, this, configured, budget, (state, committed) ->
                committed instanceof EquipmentChange.Commit commit ? changeEquipment(state, commit.change()).state()
                        : committed instanceof WeaponFire.Commit commit ? commit.apply(state)
                        : committed instanceof WeaponReload.Commit commit ? commit.apply(state)
                        : committed instanceof AbilityUse.Commit commit ? commit.apply(state) : ShieldDamage.reconcile(state, committed));
    }
    public EffectClock.Rate resourceRate(EffectState state, ResourceState account) {
        var definition = resources.get(account.key().resource());
        if (definition == null) throw new IllegalArgumentException("Unknown resource definition: " + account.key().resource());
        definition.validate(account);
        double value = resourceCalculation(state, account).map(result -> result.output().value()).orElse(definition.baseRate());
        return new EffectClock.Rate(value, definition.thresholds());
    }
    public Optional<CalculationProfile.Result> resourceCalculation(EffectState state, ResourceState account) {
        var definition = resources.get(account.key().resource());
        if (definition == null) throw new IllegalArgumentException("Unknown resource definition: " + account.key().resource());
        definition.validate(account); String holder = account.key().holder();
        var origin = new BuffInstance.Origin(holder, definition.id(), "", "");
        var query = new EffectEvent(holder, holder, origin, java.util.Set.of("chorus:resource_rate_query"),
                Map.of("resource_value", new Measure(account.value(), Unit.CHARGE), "capacity", new Measure(account.capacity(), Unit.CHARGE)),
                Map.of(), Map.of("resource", definition.id()));
        return definition.rateProfile().map(id -> calculate(state, holder, query, id, new Measure(definition.baseRate(), Unit.CHARGE_PER_SECOND), List.of()));
    }
    /** Query from the beginning of a time segment. World health is deliberately not shadowed here. */
    public List<Recovery.Offer> recoveryOffers(EffectState state) {
        validateState(state); var offers = new ArrayList<Recovery.Offer>();
        for (var source : state.sources().values()) collectRecovery(state, source, sourceId(source), source.holder(), bundle(source.bundle()), offers);
        for (var instance : state.buffs().instances().values()) {
            if (instance.pausedAt().isPresent() || instance.activeCount(state.buffs().timeMicros()) == 0) continue;
            String bundleId = buffBundles.get(instance.definition().id()); if (bundleId == null) continue;
            collectRecovery(state, new BuffRules.Scope(instance, false), "buff/" + instance.generation(), instance.key().holder(), bundle(bundleId), offers);
        }
        return List.copyOf(offers);
    }
    private void collectRecovery(EffectState state, RuleEngine.Payload scope, String instanceId, String holder,
            EffectProgram.Bundle bundle, List<Recovery.Offer> offers) {
        if (bundle.recovery().isEmpty()) return;
        // This is a rate query, not a replay of the application event. Event-specific facts are absent.
        var event = new RuleEngine.Event(0, 0, Optional.empty(), state.buffs().timeMicros(), new RuleEngine.Signal("chorus:internal/recovery_query", RuleEngine.Empty.INSTANCE));
        var evaluation = evaluation(state, new RuleEngine.Context(event, instanceId, scope, Map.of()), Map.of());
        for (var declaration : bundle.recovery()) {
            if (!declaration.condition().test(evaluation)) continue;
            var rate = declaration.rate().evaluate(evaluation); Validation.same(rate.unit(), Unit.DAMAGE_PER_SECOND);
            offers.add(new Recovery.Offer(instanceId + "/recovery/" + declaration.id(), holder, declaration.channel(), declaration.priority(),
                    rate.value(), evaluation.origin(), declaration.tags()));
        }
    }
    /** Layers are queried only when reached, in priority / FIFO order. Layer bonuses consume budget locally. */
    public ShieldDamage.Planned shields(EffectState state, DamageCommand command, double input) {
        return shields(state, command, input, DamageBasis.EMPTY);
    }
    public ShieldDamage.Planned shields(EffectState state, DamageCommand command, double input, DamageBasis basis) {
        settled(state); Numbers.nonnegative(input, "shield input");
        Objects.requireNonNull(basis);
        if (basis.outgoing().isPresent() && !command.scalingProfile().equals(Optional.of(basis.outgoing().orElseThrow().trace().profile()))) {
            throw new IllegalArgumentException("Shield damage basis differs from the attack profile");
        }
        if (command.snapshot().isEmpty()) command.shieldScalingProfile().ifPresent(id -> new Validation(buffs, Map.of(), false, profiles).multiplierProfile(id));
        var layers = state.buffs().instances().values().stream().filter(instance -> instance.key().holder().equals(command.target())
                && instance.pausedAt().isEmpty() && instance.activeCount(state.buffs().timeMicros()) > 0 && shields.containsKey(instance.definition().id()))
                .sorted(java.util.Comparator.<BuffInstance>comparingInt(instance -> shield(instance.definition().id()).priority())
                        .thenComparingLong(BuffInstance::generation)).toList();
        var bound = new ArrayList<ShieldDamage.Bound>(); double remaining = input;
        for (var instance : layers) {
            if (remaining <= 0) break;
            var shield = shield(instance.definition().id());
            if (instance.components().numbers().get(shield.capacity()) <= 0) continue;
            var event = new RuleEngine.Event(0, 0, Optional.empty(), state.buffs().timeMicros(),
                    new RuleEngine.Signal("chorus:internal/shield_query", new ShieldQuery(damageQuery(command, remaining), instance)));
            var context = new RuleEngine.Context(event, "shield/" + instance.generation(), new BuffRules.Scope(instance, false), Map.of());
            var multiplier = shield.takenMultiplier().evaluate(evaluation(state, context, Map.of()));
            Validation.same(multiplier.unit(), Unit.MULTIPLIER); Numbers.nonnegative(multiplier.value(), "shield multiplier");
            var attackScaling = shieldAttackScaling(state, command, event);
            Optional<DamageBasis.Suppression> suppression = Optional.empty();
            if (!shield.excludedAttackFactors().isEmpty()) {
                if (command.scalingProfile().isPresent() && basis.outgoing().isEmpty()) throw new IllegalArgumentException("Shield factor suppression requires the hit's resolved outgoing profile");
                suppression = Optional.of(basis.excluding(shield.excludedAttackFactors()));
            }
            double combined = multiplier.value() * attackScaling.map(result -> result.output().value()).orElse(1.0) * suppression.map(DamageBasis.Suppression::multiplier).orElse(1.0);
            var layer = new ShieldDamage.Bound(instance, shield.capacity(), combined, attackScaling, suppression); bound.add(layer);
            remaining = ShieldPlan.calculate(remaining, List.of(new ShieldPlan.Layer(Long.toString(instance.generation()),
                    instance.components().numbers().get(shield.capacity()), combined))).toVanilla();
        }
        return ShieldDamage.plan(input, bound);
    }
    private Optional<CalculationProfile.Result> shieldAttackScaling(EffectState state, DamageCommand command, RuleEngine.Event event) {
        return command.shieldScalingProfile().map(id -> {
            CalculationProfile.Result result;
            if (command.snapshot().isPresent()) {
                var snapshot = command.snapshot().orElseThrow(); snapshot.validate(command);
                result = calculateCaptured(state, command.source().owner(), event, snapshot.shieldScaling().orElseThrow().profile(),
                        new Measure(1, Unit.MULTIPLIER), snapshot.resolveShield(state, event, this));
            } else result = calculateEvent(state, command.source().owner(), event, id, new Measure(1, Unit.MULTIPLIER), List.of());
            Validation.same(result.output().unit(), Unit.MULTIPLIER); Numbers.nonnegative(result.output().value(), "shield attack multiplier"); return result;
        });
    }
    public EffectProgram.Shield shield(String id) {
        var value = shields.get(id); if (value == null) throw new IllegalArgumentException("Buff has no shield declaration: " + id); return value;
    }
    /** Maximum is evaluated in the receiving layer's scope, not the restoring rule's scope. It does not resize existing capacity. */
    public double shieldMaximum(EffectState state, BuffInstance instance) {
        validateState(state); var declaration = shield(instance.definition().id());
        if (declaration.maximum().isEmpty()) return instance.definition().components().number(declaration.capacity()).value();
        var event = new RuleEngine.Event(0, 0, Optional.empty(), state.buffs().timeMicros(), new RuleEngine.Signal("chorus:internal/shield_restore_query", RuleEngine.Empty.INSTANCE));
        var context = new RuleEngine.Context(event, "shield/" + instance.generation(), new BuffRules.Scope(instance, false), Map.of());
        var maximum = declaration.maximum().orElseThrow().evaluate(evaluation(state, context, Map.of()));
        Validation.same(maximum.unit(), Unit.DAMAGE); Numbers.nonnegative(maximum.value(), "shield maximum"); return maximum.value();
    }
    /** Each active layer owns one rate expression. Recovery never reads or invents native health. */
    public List<ShieldRecovery.Offer> shieldRecoveryOffers(EffectState state) {
        validateState(state); var offers = new ArrayList<ShieldRecovery.Offer>();
        for (var instance : state.buffs().instances().values()) {
            if (instance.pausedAt().isPresent() || instance.activeCount(state.buffs().timeMicros()) == 0) continue;
            var shield = shields.get(instance.definition().id()); if (shield == null || shield.recovery().isEmpty()) continue;
            var declaration = shield.recovery().orElseThrow();
            var event = new RuleEngine.Event(0, 0, Optional.empty(), state.buffs().timeMicros(), new RuleEngine.Signal("chorus:internal/shield_recovery_query", RuleEngine.Empty.INSTANCE));
            var context = new RuleEngine.Context(event, "shield/" + instance.generation(), new BuffRules.Scope(instance, false), Map.of());
            var evaluation = evaluation(state, context, Map.of());
            if (!declaration.condition().test(evaluation)) continue;
            var rate = declaration.rate().evaluate(evaluation); Validation.same(rate.unit(), Unit.DAMAGE_PER_SECOND);
            offers.add(new ShieldRecovery.Offer(instance, shield.capacity(), shieldMaximum(state, instance), rate.value()));
        }
        return List.copyOf(offers);
    }
    /** Only positive, active, unpaused Chorus layers qualify; native Absorption is a separate observation. */
    public boolean hasShield(EffectState state, String holder, Optional<String> tag) {
        validateState(state);
        return state.buffs().instances().values().stream().anyMatch(instance -> instance.key().holder().equals(holder)
                && instance.pausedAt().isEmpty() && instance.activeCount(state.buffs().timeMicros()) > 0
                && shields.containsKey(instance.definition().id()) && tag.map(value -> instance.definition().tags().contains(value)).orElse(true)
                && instance.components().numbers().get(shield(instance.definition().id()).capacity()) > 0);
    }
    public BuffDefinition buff(String id) {
        var value = buffs.get(id); if (value == null) throw new IllegalArgumentException("Unknown buff: " + id); return value;
    }
    private EffectProgram.Bundle bundle(String id) {
        var value = bundles.get(id); if (value == null) throw new IllegalArgumentException("Unknown bundle: " + id); return value;
    }
    private void version(String version) { if (!program.version().equals(version)) throw new IllegalArgumentException("Mixed ruleset versions"); }
    private static void localId(String id) { if (!id.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid local rule/modifier id: " + id); }
    private static void namespaced(String id) { if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid namespaced id: " + id); }
    private static <T> Map<String, T> index(List<T> values, Function<T, String> id) {
        var result = new LinkedHashMap<String, T>();
        for (var value : values) { String key = id.apply(value); namespaced(key); if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate definition: " + key); }
        return java.util.Collections.unmodifiableMap(result);
    }
    private Validation validation(EffectProgram.Bundle bundle, Map<String, ResultShape> results) {
        return new Validation(buffs, results, bundle.scope() == EffectProgram.Scope.BUFF, profiles, resources, shields);
    }
    Evaluation evaluation(EffectState state, RuleEngine.Context context, Map<String, ResultShape> results) {
        return new Evaluation(state, EffectContinuations.context(context), buffs, results, resources, context.retainedResults(), Optional.of(this));
    }
    private RuleEngine.EventRule<EffectState> compile(EffectProgram.Bundle bundle, EffectProgram.Rule rule, List<RuleEngine.EventRule<EffectState>> continuations) {
        validatePayments(rule.actions(), new HashSet<>());
        rule.condition().validate(validation(bundle, Map.of()));
        var actions = compileSteps(bundle, rule.actions(), Map.of(), Map.of(), "", bundle.id() + "/rule/" + rule.id(), continuations);
        return new RuleEngine.EventRule<>(bundle.id() + "/rule/" + rule.id(), rule.on(), (state, context) -> {
            if (context.scope() instanceof EffectSource source && !source.equals(state.sources().get(source.instance()))) {
                // Only the exact removal fact may run with an old source; ordinary stale bindings stay inactive.
                if (!context.event().signal().type().equals("chorus:source_detached")
                        || !(context.event().signal().payload() instanceof SourceChange.Fact fact)
                        || !fact.detached() || !source.equals(fact.source())) return false;
            }
            return rule.condition().test(evaluation(state, context, Map.of()));
        }, actions);
    }
    private static void validatePayments(List<EffectProgram.Step> steps, java.util.Set<String> seen) {
        for (var step : steps) {
            if (step instanceof EffectProgram.Instruction instruction && instruction.action() instanceof Action.SpendResource spend) {
                localId(spend.payment()); if (!seen.add(spend.payment())) throw new IllegalArgumentException("Duplicate payment identity in one rule: " + spend.payment());
            } else if (step instanceof EffectProgram.Branch branch) { validatePayments(branch.then(), seen); validatePayments(branch.otherwise(), seen); }
            else if (step instanceof EffectProgram.ForEach loop) validatePayments(loop.body(), seen);
            else if (step instanceof EffectProgram.After after) validatePayments(after.body(), new HashSet<>());
            else if (step instanceof EffectProgram.Projectile projectile) validatePayments(projectile.body(), new HashSet<>());
        }
    }

    private Evaluation scopedEvaluation(EffectState state, RuleEngine.Context context, Map<String, ResultShape> results, Map<String, String> slots) {
        var bindings = new HashMap<String, RuleEngine.ActionResult>();
        slots.forEach((name, slot) -> { if (context.bindings().containsKey(slot)) bindings.put(name, context.bindings().get(slot)); });
        var normalized = EffectContinuations.context(context);
        return new Evaluation(state, new RuleEngine.Context(normalized.event(), normalized.ruleInstance(), normalized.scope(), bindings, normalized.operation(), normalized.retainedResults(), normalized.pendingCommand()),
                buffs, results, resources, normalized.retainedResults(), Optional.of(this));
    }
    private List<RuleEngine.Instruction<EffectState>> compileSteps(EffectProgram.Bundle bundle, List<EffectProgram.Step> steps,
            Map<String, ResultShape> outerResults, Map<String, String> outerSlots, String path, String ownerRule, List<RuleEngine.EventRule<EffectState>> continuations) {
        var results = new LinkedHashMap<>(outerResults); var slots = new LinkedHashMap<>(outerSlots);
        var output = new ArrayList<RuleEngine.Instruction<EffectState>>();
        RuleEngine.Action<EffectState> noop = (state, _) -> new RuleEngine.Local<>(state, RuleEngine.Empty.INSTANCE, List.of());
        for (int index = 0; index < steps.size(); index++) {
            Map<String, ResultShape> previous = Map.copyOf(results); Map<String, String> previousSlots = Map.copyOf(slots);
            switch (steps.get(index)) {
                case EffectProgram.Instruction instruction -> {
                    ResultShape shape = instruction.action().validate(validation(bundle, previous));
                    // Private cost slots expose the latest instruction result for diagnostics.
                    // Frame.retainedResults preserves claim budgets independently of lexical loop cleanup.
                    String slot = instruction.bind().isEmpty() ? shape.carriesCost() ? path + index + "/cost" : "" : path + instruction.bind();
                    if (!instruction.bind().isEmpty()) {
                        localId(instruction.bind());
                        if (results.putIfAbsent(instruction.bind(), shape) != null) throw new IllegalArgumentException("Duplicate or shadowed result binding: " + instruction.bind());
                        slots.put(instruction.bind(), slot);
                    }
                    output.add(new RuleEngine.Instruction<>(new RuleEngine.Action<>() {
                        @Override public RuleEngine.Outcome<EffectState> step(EffectState state, RuleEngine.Context context) {
                            return instruction.action().execute(scopedEvaluation(state, context, previous, previousSlots));
                        }
                        @Override public RuleEngine.Local<EffectState> complete(EffectState state, RuleEngine.Context context, RuleEngine.ActionResult receipt) {
                            return instruction.action().complete(scopedEvaluation(state, context, previous, previousSlots), receipt);
                        }
                    }, slot));
                }
                case EffectProgram.Branch branch -> {
                    branch.condition().validate(validation(bundle, previous));
                    var yes = compileSteps(bundle, branch.then(), previous, previousSlots, path + index + "/then/", ownerRule, continuations);
                    var no = compileSteps(bundle, branch.otherwise(), previous, previousSlots, path + index + "/else/", ownerRule, continuations);
                    output.add(new RuleEngine.Instruction<>(noop, "", (state, context) -> branch.condition().test(scopedEvaluation(state, context, previous, previousSlots)), yes.size() + 2));
                    output.addAll(yes);
                    output.add(new RuleEngine.Instruction<>(noop, "", (_, _) -> false, no.size() + 1));
                    output.addAll(no);
                }
                case EffectProgram.After after -> {
                    Action.validateDuration(after.delay(), validation(bundle, previous));
                    var capturedResults = new LinkedHashMap<String, ResultShape>(); var capturedSlots = new java.util.TreeMap<String, String>();
                    previous.forEach((name, shape) -> {
                        if (!shape.carriesCost()) { capturedResults.put(name, shape); capturedSlots.put(name, previousSlots.get(name)); }
                    });
                    String definition = ownerRule + "/after/" + path + index;
                    var body = new ArrayList<RuleEngine.Instruction<EffectState>>();
                    capturedSlots.forEach((name, slot) -> body.add(new RuleEngine.Instruction<>((state, context) -> {
                        var pending = (EffectContinuations.Pending) context.scope();
                        var value = pending.bindings().get(slot);
                        if (value == null) throw new IllegalArgumentException("Continuation is missing captured binding: " + name);
                        return new RuleEngine.Local<>(state, value, List.of());
                    }, slot)));
                    body.addAll(compileSteps(bundle, after.body(), capturedResults, capturedSlots, path + index + "/after/", ownerRule, continuations));
                    continuations.add(new RuleEngine.EventRule<>(definition, EffectContinuations.EVENT,
                            (state, context) -> ((EffectContinuations.Pending) context.scope()).active(state), body));
                    output.add(new RuleEngine.Instruction<>((state, context) -> {
                        var e = scopedEvaluation(state, context, previous, previousSlots);
                        long delay = Action.micros(after.delay(), e);
                        var owner = after.lifetime() == EffectContinuations.Lifetime.SOURCE ? Optional.of(EffectTimers.Owner.of(e.context().scope())) : Optional.<EffectTimers.Owner>empty();
                        if (owner.isPresent() && !owner.orElseThrow().active(state.sources(), state.buffs())) throw new IllegalArgumentException("Cannot schedule a source-bound continuation for an inactive source");
                        var captured = new LinkedHashMap<String, RuleEngine.ActionResult>();
                        capturedSlots.forEach((name, slot) -> captured.put(slot, e.context().bindings().get(name)));
                        var operation = context.operation();
                        String id = String.format(java.util.Locale.ROOT, "continuation/%020d/%010d/%010d", operation.frame(), operation.pc(), operation.invocation());
                        var pending = new EffectContinuations.Pending(id, definition, program.version(), e.context().scope(), e.context().event(), captured, owner);
                        var lifetime = owner.flatMap(EffectTimers.Owner::buff);
                        boolean paused = lifetime.isPresent() && e.ownBuff().pausedAt().isPresent();
                        var timer = new EffectState.Timer(id, paused ? Long.MAX_VALUE : Math.addExact(state.buffs().timeMicros(), delay), 0, 1,
                                new RuleEngine.Signal(EffectContinuations.EVENT, pending), lifetime,
                                paused ? java.util.OptionalLong.of(delay) : java.util.OptionalLong.empty());
                        return new RuleEngine.Local<>(state.schedule(timer), RuleEngine.Empty.INSTANCE, List.of());
                    }, ""));
                }
                case EffectProgram.Projectile projectile -> {
                    projectile.spec().validate(validation(bundle, previous)); localId(projectile.bind());
                    if (previous.containsKey(projectile.bind())) throw new IllegalArgumentException("Shadowed projectile impact binding");
                    var capturedResults = new LinkedHashMap<String, ResultShape>(); var capturedSlots = new java.util.TreeMap<String, String>();
                    previous.forEach((name, shape) -> { if (!shape.carriesCost()) { capturedResults.put(name, shape); capturedSlots.put(name, previousSlots.get(name)); } });
                    String definition = ownerRule + "/projectile/" + path + index, impactSlot = path + index + "/impact/" + projectile.bind();
                    var nestedResults = new LinkedHashMap<>(capturedResults); var nestedSlots = new java.util.TreeMap<>(capturedSlots);
                    nestedResults.put(projectile.bind(), ResultShape.PROJECTILE_IMPACT); nestedSlots.put(projectile.bind(), impactSlot);
                    var body = new ArrayList<RuleEngine.Instruction<EffectState>>();
                    nestedSlots.forEach((name, slot) -> body.add(new RuleEngine.Instruction<>((state, context) -> {
                        var value = ((EffectContinuations.Pending) context.scope()).bindings().get(slot);
                        if (value == null) throw new IllegalArgumentException("Projectile is missing captured binding: " + name);
                        return new RuleEngine.Local<>(state, value, List.of());
                    }, slot)));
                    body.addAll(compileSteps(bundle, projectile.body(), nestedResults, nestedSlots, path + index + "/projectile/", ownerRule, continuations));
                    continuations.add(new RuleEngine.EventRule<>(definition, com.imdomestic.chorus.effect.projectile.ProjectileFlight.EVENT, (_, _) -> true, body));
                    output.add(new RuleEngine.Instruction<>(new RuleEngine.Action<>() {
                        @Override public RuleEngine.Outcome<EffectState> step(EffectState state, RuleEngine.Context context) {
                            var e = scopedEvaluation(state, context, previous, previousSlots); var spec = projectile.spec();
                            var captured = new LinkedHashMap<String, RuleEngine.ActionResult>();
                            capturedSlots.forEach((name, slot) -> captured.put(slot, e.context().bindings().get(name)));
                            var op = context.operation(); String id = "projectile/" + op.frame() + "/" + op.pc() + "/" + op.invocation();
                            var pending = new EffectContinuations.Pending(id, definition, program.version(), e.context().scope(), e.context().event(), captured, Optional.empty());
                            return new RuleEngine.Await<>(new com.imdomestic.chorus.effect.projectile.ProjectileFlight.Launch(
                                    previous.get(spec.position()).position(e.context().bindings().get(spec.position())),
                                    previous.get(spec.direction()).direction(e.context().bindings().get(spec.direction())), spec.resolve(e), e.origin().owner(), pending, impactSlot));
                        }
                        @Override public RuleEngine.Local<EffectState> complete(EffectState state, RuleEngine.Context context, RuleEngine.ActionResult result) {
                            var receipt = (com.imdomestic.chorus.effect.projectile.ProjectileFlight.Receipt) result;
                            if (!receipt.launch().equals(context.command(com.imdomestic.chorus.effect.projectile.ProjectileFlight.Launch.class))) throw new IllegalArgumentException("Projectile receipt does not match launch");
                            return new RuleEngine.Local<>(state, receipt, List.of());
                        }
                    }, ""));
                }
                case EffectProgram.ForEach loop -> {
                    localId(loop.bind());
                    var shape = validation(bundle, previous).result(loop.collection()); shape.requireTargets();
                    var nestedResults = new LinkedHashMap<>(previous); var nestedSlots = new LinkedHashMap<>(previousSlots);
                    if (nestedResults.putIfAbsent(loop.bind(), shape.targetElement()) != null) throw new IllegalArgumentException("Duplicate or shadowed loop binding: " + loop.bind());
                    String loopPath = path + index + "/each/", slot = loopPath + loop.bind();
                    nestedSlots.put(loop.bind(), slot);
                    var body = compileSteps(bundle, loop.body(), nestedResults, nestedSlots, loopPath, ownerRule, continuations);
                    output.add(new RuleEngine.Instruction<>(new RuleEngine.ForEach<>((state, context) -> {
                        var e = scopedEvaluation(state, context, previous, previousSlots);
                        return shape.targets(e.context().bindings().get(loop.collection()));
                    }, body.size()), slot));
                    output.addAll(body);
                    output.add(new RuleEngine.Instruction<>(new RuleEngine.EndEach<>(body.size()), ""));
                }
            }
        }
        return List.copyOf(output);
    }

    @Override public List<RuleEngine.RuleBinding> resolve(EffectState state, RuleEngine.Event event) {
        validateState(state);
        if (event.signal().type().equals(EffectContinuations.EVENT) || event.signal().type().equals(com.imdomestic.chorus.effect.projectile.ProjectileFlight.EVENT)) {
            if (!(event.signal().payload() instanceof EffectContinuations.Pending pending) || !program.version().equals(pending.version())
                    || !continuationIds.contains(pending.definition()) || pending.cause().timeMicros() > event.timeMicros()
                    || event.signal().type().equals(EffectContinuations.EVENT) && pending.cause().timeMicros() == event.timeMicros()) {
                throw new IllegalArgumentException("Unknown or incompatible delayed continuation");
            }
            return List.of(new RuleEngine.RuleBinding(pending.id(), pending.definition(), pending));
        }
        if (event.signal().type().equals(Recovery.EVENT)) return Recovery.resolve(event);
        if (event.signal().type().equals(WeaponFire.REQUEST) || event.signal().type().equals(WeaponReload.REQUEST) || event.signal().type().equals(WeaponReload.DUE))
            return List.of(new RuleEngine.RuleBinding(event.signal().type(), event.signal().type(), RuleEngine.Empty.INSTANCE));
        if (event.signal().type().equals(AbilityChange.EVENT) || event.signal().type().equals(AbilityUse.EVENT) || event.signal().type().equals(SourceChange.EVENT)) return List.of(new RuleEngine.RuleBinding(event.signal().type(), event.signal().type(), RuleEngine.Empty.INSTANCE));
        if (event.signal().type().equals(SourceBatch.EVENT)) return List.of(new RuleEngine.RuleBinding(SourceBatch.EVENT, SourceBatch.EVENT, RuleEngine.Empty.INSTANCE));
        if (event.signal().type().equals(EquipmentChange.EVENT)) return List.of(new RuleEngine.RuleBinding(EquipmentChange.EVENT, EquipmentChange.EVENT, RuleEngine.Empty.INSTANCE));
        var result = new ArrayList<RuleEngine.RuleBinding>();
        if (event.signal().type().equals("chorus:source_detached") && event.signal().payload() instanceof SourceChange.Fact fact && fact.detached()) {
            validateSource(fact.source());
            for (var rule : sourceRules.get(fact.source().bundle())) if (rule.eventType().equals(event.signal().type())) {
                result.add(new RuleEngine.RuleBinding(sourceId(fact.source()) + "/detached/" + rule.definition(), rule.definition(), fact.source()));
            }
        }
        if (event.signal().type().equals("chorus:weapon_stowed") || event.signal().type().equals("chorus:weapon_drawn")) {
            String definition = "chorus:internal/" + (event.signal().type().endsWith("stowed") ? "weapon_stowed" : "weapon_drawn");
            result.add(new RuleEngine.RuleBinding(definition, definition, RuleEngine.Empty.INSTANCE));
        }
        if (event.signal().type().equals("chorus:ability_used") && event.signal().payload() instanceof AbilityUse.Used used) {
            if (!used.definition().equals(abilities.get(used.definition().id()))) throw new IllegalArgumentException("Incompatible accepted ability");
            var rule = abilityRules.get(used.definition().id());
            result.add(new RuleEngine.RuleBinding("cast/" + used.receipt().cast(), rule.definition(), new AbilityUse.Scope(used.event())));
        }
        if (event.signal().type().equals(WeaponFire.ACCEPTED) && event.signal().payload() instanceof WeaponFire.Accepted accepted) {
            if (!accepted.definition().equals(weapons.definition(accepted.shot().gear().definition())) || accepted.definition().fire().isEmpty())
                throw new IllegalArgumentException("Incompatible accepted weapon fire");
            var rule = fireRules.get(accepted.definition().item());
            result.add(new RuleEngine.RuleBinding("shot/" + accepted.shot().token(), rule.definition(), new WeaponFire.Scope(accepted.event())));
        }
        for (var source : state.sources().values()) for (var rule : sourceRules.get(source.bundle())) if (rule.eventType().equals(event.signal().type())) {
            if (event.signal().type().equals("chorus:source_detached") && event.signal().payload() instanceof SourceChange.Fact fact
                    && fact.detached() && source.equals(fact.source())) continue;
            result.add(new RuleEngine.RuleBinding(sourceId(source) + "/" + rule.definition(), rule.definition(), source));
        }
        result.addAll(buffRules.resolve(state, event)); return List.copyOf(result);
    }
    private static String sourceId(EffectSource source) { return "source/" + source.instance().length() + ":" + source.instance(); }
    private void validateState(EffectState state) {
        weapons.validate(state);
        state.sources().values().forEach(this::validateSource);
        state.abilities().values().forEach(loadout -> loadout.slots().forEach(this::validateSelection));
        state.equipment().forEach((holder, loadout) -> equipment.sources(holder, loadout).forEach((id, source) -> {
            if (!source.equals(state.sources().get(id))) throw new IllegalArgumentException("Equipment source projection is inconsistent: " + id);
        }));
        for (var account : state.resources().values()) {
            var definition = resources.get(account.key().resource()); if (definition != null) definition.validate(account);
        }
        for (var instance : state.buffs().instances().values()) if (!instance.definition().equals(buffs.get(instance.definition().id()))) {
            throw new IllegalArgumentException("Unregistered or incompatible live buff: " + instance.definition().id());
        }
    }
    private static RuleEngine.EventRule<EffectState> weaponRule(boolean stowed) {
        String type = stowed ? "weapon_stowed" : "weapon_drawn";
        return new RuleEngine.EventRule<>("chorus:internal/" + type, "chorus:" + type, (_, _) -> true,
                List.of(new RuleEngine.Instruction<>((state, context) -> {
                    var event = EffectTimers.event(context.event().signal().payload()).orElseThrow(() -> new IllegalArgumentException("Weapon event is missing its facts"));
                    var result = Buffs.weaponState(state.buffs(), event.actor(), event.source().weapon(), stowed);
                    return new RuleEngine.Local<>(state.withBuffs(result.store()), result.receipt(), result.signals());
                }, "")));
    }

    private void validateModifier(EffectProgram.Bundle bundle, EffectProgram.Modifier modifier) {
        var profile = profiles.get(modifier.profile());
        if (profile == null) throw new IllegalArgumentException("Unknown modifier profile: " + modifier.profile());
        var validation = validation(bundle, Map.of()); modifier.condition().validate(validation);
        Unit expected = profile.inputUnit(); boolean found = false;
        for (var step : profile.steps()) {
            if (step instanceof CalculationStep.Transform transform) expected = transform.outputUnit();
            if (!step.id().equals(modifier.stage())) continue;
            if (!(step instanceof CalculationStep.Apply apply) || apply.operation() != modifier.operation() || !apply.percentOf().equals(modifier.percentOf())) {
                throw new IllegalArgumentException("Modifier disagrees with profile stage: " + modifier.stage());
            }
            if (!apply.group().paths().contains(modifier.group())) throw new IllegalArgumentException("Unknown modifier group: " + modifier.group());
            expected = switch (apply.operation()) { case ADD, REPLACE -> expected; case MULTIPLY, BASE_PERCENT -> Unit.DELTA; case RESIST -> Unit.RESISTANCE; };
            found = true; break;
        }
        if (!found) throw new IllegalArgumentException("Missing modifier stage: " + modifier.stage());
        Validation.same(modifier.value().unit(validation), expected);
        if (modifier.stackingKey().isBlank()) throw new IllegalArgumentException("Missing modifier family");
        if (modifier.multiplicity() == EffectProgram.Multiplicity.STACK && bundle.scope() != EffectProgram.Scope.BUFF) throw new IllegalArgumentException("Stack multiplicity needs a buff source");
    }

    private static EffectEvent damageQuery(DamageCommand command, double input) {
        return new EffectEvent(command.source().owner(), command.target(), command.source(), command.tags(),
                Map.of("incoming_damage", new Measure(input, Unit.DAMAGE)), Map.of(), Map.of("damage_type", command.damageType()), command.impact());
    }
    private static RuleEngine.Event queryEvent(EffectState state, RuleEngine.Payload query) {
        return new RuleEngine.Event(0, 0, Optional.empty(), state.buffs().timeMicros(), new RuleEngine.Signal("chorus:query", query));
    }
    /** Read current capacity from the affected weapon holder; the triggering action's origin is irrelevant. */
    public com.imdomestic.chorus.effect.ammo.AmmoCapacity.View ammoCapacity(EffectState state, String weapon) {
        var account = state.ammunition().get(weapon);
        if (account == null) throw new IllegalArgumentException("Missing ammunition account: " + weapon);
        return ammoCapacity(state, account, java.util.Set.of());
    }
    public com.imdomestic.chorus.effect.ammo.AmmoCapacity.View ammoCapacity(EffectState state, com.imdomestic.chorus.effect.ammo.AmmoState account, java.util.Set<String> dependencies) {
        if (account.capacityProfile().isEmpty()) return com.imdomestic.chorus.effect.ammo.AmmoCapacity.View.fixed(account);
        if (!account.equals(state.ammunition().get(account.weapon()))) throw new IllegalArgumentException("Capacity query requires the current account");
        var path = new HashSet<>(dependencies);
        if (!path.add(account.weapon())) throw new IllegalArgumentException("Circular ammunition capacity expression: " + account.weapon());
        var spec = account.capacityProfile().orElseThrow(); new Validation(buffs, Map.of(), false, profiles).ammoProfile(spec.profile());
        var query = new EffectEvent(spec.holder(), account.weapon(), new BuffInstance.Origin(spec.holder(), account.weapon(), account.weapon(), ""),
                java.util.Set.of("chorus:ammo_capacity_query"), Map.of("unmodified_capacity", new Measure(account.capacity(), Unit.ROUND), "magazine", new Measure(account.magazine(), Unit.ROUND)),
                Map.of("infinite_reserves", account.reserve().isEmpty()), Map.of("weapon", account.weapon()));
        var result = calculateEvent(state, spec.holder(), queryEvent(state, new com.imdomestic.chorus.effect.ammo.AmmoCapacity.Query(query, path)),
                spec.profile(), new Measure(account.capacity(), Unit.ROUND), List.of());
        return new com.imdomestic.chorus.effect.ammo.AmmoCapacity.View(account, com.imdomestic.chorus.effect.ammo.AmmoState.rounds(result.output().value()), Optional.of(result));
    }
    private void settled(EffectState state) {
        validateState(state);
        if (state.buffs().nextDeadline() <= state.buffs().timeMicros()) throw new IllegalStateException("Query requires settled buff expirations");
    }
    /** Capture an attack before its target is known. No final group is flattened and no world/entity object is retained. */
    public DamageSnapshot captureDamage(EffectState state, DamageCommand attack) {
        settled(state);
        if (attack.snapshot().isPresent()) throw new IllegalArgumentException("Cannot capture an already captured attack");
        if (!attack.impact().numbers().isEmpty()) throw new IllegalArgumentException("Capture requires an attack without impact measurements");
        String profileId = attack.scalingProfile().orElseThrow(() -> new IllegalArgumentException("Snapshot requires an explicit damage profile"));
        new Validation(buffs, Map.of(), false, profiles).damageProfile(profileId);
        var query = damageQuery(attack, attack.amount());
        // Deliberately erase victim: even an instant hit uses the same partial binding path.
        var event = queryEvent(state, new EffectEvent(query.actor(), "", query.source(), query.tags(), query.numbers(), query.flags(), query.references()));
        var captured = captureContributions(state, attack.source().owner(), event, profileId);
        var shieldScaling = attack.shieldScalingProfile().map(id -> new DamageSnapshot.ShieldScaling(
                new Validation(buffs, Map.of(), false, profiles).multiplierProfile(id), captureContributions(state, attack.source().owner(), event, id)));
        return new DamageSnapshot(state.buffs().timeMicros(), state.mode(), attack, profiles.get(profileId), buffs, resources, captured, shieldScaling);
    }
    private List<DamageSnapshot.Contribution> captureContributions(EffectState state, String holder, RuleEngine.Event event, String profileId) {
        var captured = new ArrayList<DamageSnapshot.Contribution>();
        visitModifiers(state, holder, event, profileId, site -> {
            if (site.modifier().evaluate() != EffectProgram.Evaluate.ON_USE) return;
            var condition = site.modifier().condition().snapshot(site.evaluation());
            if (condition instanceof Condition.Constant c && !c.value()) return;
            var value = site.modifier().value().snapshot(site.evaluation());
            var scope = new EffectSource(site.instance(), site.definition(), site.evaluation().self(), site.evaluation().origin(), java.util.Set.of());
            captured.add(new DamageSnapshot.Contribution(site.instance(), site.source(), site.definition(), program.version(), scope, site.modifier(), value, condition));
        });
        return List.copyOf(captured);
    }
    /** Query a settled domain snapshot. Legacy explicit frozen inputs remain the caller's responsibility. */
    public CalculationProfile.Result calculate(EffectState state, String holder, EffectEvent query, String profileId, Measure base,
            List<NumericContribution> frozen) {
        return calculateEvent(state, holder, queryEvent(state, query), profileId, base, frozen);
    }
    private CalculationProfile.Result calculateEvent(EffectState state, String holder, RuleEngine.Event event, String profileId, Measure base,
            List<NumericContribution> frozen) {
        settled(state);
        var profile = profiles.get(profileId); if (profile == null) throw new IllegalArgumentException("Unknown profile: " + profileId);
        var contributions = new ArrayList<>(frozen);
        visitModifiers(state, holder, event, profileId, site -> {
            if (site.modifier().condition().test(site.evaluation())) contributions.add(site.numeric(site.modifier().value().evaluate(site.evaluation())));
        });
        return profile.calculate(base, contributions);
    }
    private record ModifierSite(Evaluation evaluation, EffectProgram.Modifier modifier, String instance, String source, String definition, String version) {
        NumericContribution numeric(Measure amount) {
            return new NumericContribution(instance + "/modifier/" + modifier.id(), modifier.stage(), modifier.group(), modifier.operation(), amount,
                    modifier.percentOf(), modifier.stackingKey(), new NumericContribution.Source(definition, source, modifier.reference(), modifier.confidence(), version), modifier.priority());
        }
    }
    private void visitModifiers(EffectState state, String holder, RuleEngine.Event event, String profile, java.util.function.Consumer<ModifierSite> visitor) {
        var query = EffectTimers.event(event.signal().payload()).orElseThrow(() -> new IllegalArgumentException("Missing modifier query"));
        for (var source : state.sources().values()) if (source.holder().equals(holder)) {
            visitBundle(state, event, source, sourceId(source), source.instance(), bundle(source.bundle()), profile, null, visitor);
        }
        for (var instance : state.buffs().instances().values()) {
            if (!instance.key().holder().equals(holder) || instance.activeCount(state.buffs().timeMicros()) == 0 || !instance.affects(query.source().weapon(), query.source().ability())) continue;
            String bundleId = buffBundles.get(instance.definition().id()); if (bundleId == null) continue;
            String id = "buff/" + instance.generation();
            visitBundle(state, event, new BuffRules.Scope(instance, false), id, instance.origin().source().isEmpty() ? id : instance.origin().source(),
                    bundle(bundleId), profile, instance, visitor);
        }
    }
    private void visitBundle(EffectState state, RuleEngine.Event event, RuleEngine.Payload scope, String instanceId, String sourceId,
            EffectProgram.Bundle bundle, String profile, BuffInstance buff, java.util.function.Consumer<ModifierSite> visitor) {
        var evaluation = evaluation(state, new RuleEngine.Context(event, instanceId, scope, Map.of()), Map.of());
        for (var modifier : bundle.modifiers()) {
            if (!modifier.profile().equals(profile)) continue;
            if (modifier.multiplicity() == EffectProgram.Multiplicity.STACK) {
                for (var stack : buff.stacks()) visitor.accept(new ModifierSite(evaluation, modifier, instanceId + "/stack/" + stack.id(),
                        stack.origin().source().isEmpty() ? sourceId : stack.origin().source(), bundle.id(), program.version()));
            } else visitor.accept(new ModifierSite(evaluation, modifier, instanceId, sourceId, bundle.id(), program.version()));
        }
    }
}
