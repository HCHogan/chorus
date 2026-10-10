package com.imdomestic.chorus.test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(NeoForgeGameTests.ID)
public final class NeoForgeGameTests {
    public static final String ID = "chorus_gametest";
    private record Case(Object owner, Method method, GameCase metadata, String name) {
        void run(GameTestHelper helper) {
            try { method.invoke(owner, helper); }
            catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException error) throw error;
                if (failure.getCause() instanceof Error error) throw error;
                throw new IllegalStateException("GameTest failed: " + name, failure.getCause());
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
    }
    public NeoForgeGameTests(IEventBus bus) {
        var cases = new ArrayList<Case>();
        for (var owner : List.of(new DamageGameTest(), new NativeRuntimeGameTest(), new ShieldGameTest(), new CombatProfileGameTest(), new HealingGameTest(), new ScheduledEffectsGameTest(), new RecoveryGameTest(), new EffectProgramsGameTest(), new ProgramImportsGameTest(),
                new ResourceRefundGameTest(), new RetainedCostGameTest(), new AmmoGameTest(), new AmmoCapacityGameTest(), new WeaponReloadGameTest(), new ImpulseGameTest(), new AmplifiedGameTest(), new ActionGateGameTest(), new NativeRangedGameTest(), new NativeMeleeGameTest(), new NativeMovementGameTest(), new NativeMotionGameTest(), new HorizontalSpeedGameTest(), new DisplacementGameTest(), new SuspendGameTest(), new FreezeGameTest(), new SlowGameTest(), new DuranceGameTest(), new HealthPaymentGameTest(), new BreakoutGameTest(), new FreezeDamageGameTest(), new BuffRemovalGameTest(), new SuppressionGameTest(), new SuppressionNativeGameTest(), new NativeAttributeGameTest(), new WeaponFireGameTest(), new ShotGroupGameTest(), new DamageConsumptionGameTest(), new NativeConsumptionGameTest(), new DamageGroupGameTest(), new OneTwoPunchGameTest(), new ReactionBindingGameTest(), new ProcPolicyGameTest(), new DamageBatchGameTest(), new BoltChargeGameTest(), new RollingStormGameTest(), new ClownCartridgeGameTest(), new AdrenalineJunkieGameTest(), new TargetIterationGameTest(), new TargetSelectionGameTest(), new VolatileGameTest(), new DamageSnapshotGameTest(), new DamageSnapshotComponentGameTest(), new SolarGameTest(), new EmberOfCharGameTest(), new EmberOfEruptionGameTest(), new AttributeQueryGameTest(), new SolarFragmentStatsGameTest(), new IncandescentGameTest(), new ContinuationGameTest(), new FixedPositionGameTest(), new KineticTremorsGameTest(), new ImpactSnapshotGameTest(), new ActionOriginGameTest(), new EntityObservationGameTest(), new JoltGameTest(), new VoltshotGameTest(), new VoltshotWeaponGameTest(), new TargetMembershipGameTest(), new HealingRiftGameTest(), new RiftShieldGameTest(), new ShieldRecoveryGameTest(), new LayerProfileGameTest(), new PrecisionShieldGameTest(), new EquipmentGameTest(), new PlayerEquipmentGameTest(), new EquipmentNetworkGameTest(), new AbilityGameTest(), new SpatialQueryGameTest(), new ArcboltGameTest(), new PickupGameTest(), new FirespriteGameTest(), new EventBuffObservationGameTest(), new EventEntityObservationGameTest(), new EventPositionObservationGameTest(), new EmberOfSearingGameTest(), new EmberOfMercyGameTest(), new RadiantGameTest(), new EmberOfEmpyreanGameTest(), new ProjectileGameTest(), new ProjectileCollisionGameTest(), new ProjectileTrackingGameTest(), new ProjectileDestinationGameTest(), new ProjectileCatchGameTest(), new DamageTallyGameTest(), new StrandDefenseGameTest(), new ContinuityGameTest(), new ThreadedSpikeGameTest(), new EnergyGainGameTest(), new PugilistGameTest(), new DemolitionistGameTest(), new WellspringGameTest(), new SurplusGameTest(), new ProfilePipelineGameTest(), new RampageGameTest(), new FrenzyGameTest(), new NeoForgePlayerEquipmentGameTest(), new NeoForgeDamagePipelineGameTest(), new NeoForgeHealingGameTest())) {
            Arrays.stream(owner.getClass().getDeclaredMethods()).filter(method -> method.isAnnotationPresent(GameCase.class))
                    .sorted(Comparator.comparing(Method::getName)).forEach(method -> {
                        String name = (owner.getClass().getSimpleName() + "_" + method.getName()).replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
                        cases.add(new Case(owner, method, method.getAnnotation(GameCase.class), name));
                    });
        }
        DeferredRegister<Consumer<GameTestHelper>> functions = DeferredRegister.create(Registries.TEST_FUNCTION, ID);
        cases.forEach(test -> functions.register(test.name(), () -> test::run));
        functions.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var environments = cases.stream().map(test -> test.metadata().environment()).distinct()
                    .collect(java.util.stream.Collectors.toMap(name -> name, name -> event.registerEnvironment(Identifier.parse(name))));
            for (var test : cases) {
                var data = new TestData<>(environments.get(test.metadata().environment()), ResourceKey.create(Registries.DIMENSION, Identifier.parse(test.metadata().dimension())),
                        id("empty"), test.metadata().maxTicks(), 0, true, Rotation.NONE, false, 1, 1, false, 1);
                event.registerTest(id(test.name()), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id(test.name())), data));
            }
        });
    }
    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(ID, path); }
}
