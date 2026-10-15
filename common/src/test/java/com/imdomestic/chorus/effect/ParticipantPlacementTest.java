package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.resource.ParticipantPlacement.*;
import static org.junit.jupiter.api.Assertions.*;

import com.imdomestic.chorus.effect.ability.AbilityLoadout;
import com.imdomestic.chorus.effect.actor.ActorBinding;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ParticipantPlacementTest {
    static final String A = "test:a", B = "test:b", C = "test:c", H = "holder", OTHER = "other", WEAPON = "weapon";
    static final Loadout.Gear GEAR = new Loadout.Gear(WEAPON, "test:gun", Map.of());
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin(H, "credit", WEAPON, "");
    static final Measure SECOND = new Measure(1, Unit.SECOND);
    static EffectState empty() { return EffectState.empty(); }
    static Map<String, EffectState> domains() { return Map.of(A, empty(), B, empty(), C, empty()); }
    static EffectSource source(String holder, BuffInstance.Origin origin) { return new EffectSource("source", "test:source", holder, origin, Set.of()); }
    static EffectState buff(String holder, BuffInstance.Origin origin) throws Exception {
        var definition = EffectTestSupport.load("damage_group").buff("test:next_hit");
        var state = empty(); return state.withBuffs(Buffs.grant(state.buffs(), definition, holder, holder, origin, 1, 1, 3_000_000).store());
    }

    @Test void emptyFacetsRemainExplicitAndIndependentOfResourceAccounts() {
        var states = new TreeMap<>(domains());
        states.put(C, empty().withResource(new ResourceState(new ResourceState.Key(H, "test:energy"), 1, 1, 0)));
        var placed = adopt(Directory.EMPTY, Map.of(H, A), Map.of(H, B), states);
        assertEquals(A, placed.owner(Facet.CHARACTER, H).domain()); assertEquals(B, placed.owner(Facet.BUFFS, H).domain());
        assertNotEquals(placed.owner(Facet.CHARACTER, H).generation(), placed.owner(Facet.BUFFS, H).generation());
        assertEquals(3, placed.nextGeneration()); assertTrue(states.get(A).sources().isEmpty()); assertTrue(states.get(B).buffs().instances().isEmpty());
        assertSame(placed.owner(Facet.CHARACTER, H), placed.require(Facet.CHARACTER, H, A));
        assertThrows(IllegalStateException.class, () -> placed.require(Facet.BUFFS, H, A));
        assertThrows(IllegalArgumentException.class, () -> placed.owner(Facet.CHARACTER, OTHER));
    }

    @Test void emptyDirectoryDoesNotInferOwnersFromExistingState() throws Exception {
        var state = buff(H, ORIGIN).withSource(source(H, ORIGIN));
        var states = Map.of(A, state, B, state);
        assertDoesNotThrow(() -> validate(Directory.EMPTY, states));
        assertSame(Directory.EMPTY, adopt(Directory.EMPTY, Map.of(), Map.of(), states));
        assertThrows(IllegalArgumentException.class, () -> Directory.EMPTY.owner(Facet.BUFFS, H));
    }

    @Test void repeatedEnrollmentIsIdempotentButCannotSilentlyRelocateEitherFacet() {
        var placed = enroll(Directory.EMPTY, Map.of(H, A), Map.of(H, B));
        assertSame(placed, enroll(placed, Map.of(H, A), Map.of(H, B)));
        assertSame(placed, enroll(placed, Map.of(), Map.of()));
        assertThrows(IllegalStateException.class, () -> enroll(placed, Map.of(H, B), Map.of()));
        assertThrows(IllegalStateException.class, () -> enroll(placed, Map.of(), Map.of(H, C)));
        assertEquals(A, placed.owner(Facet.CHARACTER, H).domain()); assertEquals(B, placed.owner(Facet.BUFFS, H).domain());
        assertEquals(3, placed.nextGeneration());
    }

    @Test void rejectedBatchOrAdoptionPublishesNoPartialEnrollment() {
        var placed = enroll(Directory.EMPTY, Map.of(H, A), Map.of());
        assertThrows(IllegalStateException.class, () -> enroll(placed, Map.of("a-new", B, H, C), Map.of(OTHER, A)));
        assertThrows(IllegalStateException.class, () -> adopt(placed, Map.of(OTHER, B), Map.of(),
                Map.of(A, empty(), B, empty().withSource(source(H, ORIGIN)))));
        assertEquals(Map.of(H, new Lease(A, 1)), placed.characters()); assertTrue(placed.buffs().isEmpty()); assertEquals(2, placed.nextGeneration());
    }

    @Test void adoptionValidatesEveryRegisteredDomainEvenWhenTheNewBatchIsEmpty() {
        var placed = enroll(Directory.EMPTY, Map.of(H, A), Map.of(H, C));
        assertThrows(IllegalArgumentException.class, () -> adopt(placed, Map.of(), Map.of(), Map.of(A, empty(), B, empty())));
        assertThrows(IllegalStateException.class, () -> adopt(placed, Map.of(), Map.of(),
                Map.of(A, empty(), B, empty().withSource(source(H, ORIGIN)), C, empty())));
        assertSame(placed, adopt(placed, Map.of(), Map.of(), domains()));
    }

    @Test void eachCharacterComponentMustStayInItsExplicitDomain() {
        var directory = enroll(Directory.EMPTY, Map.of(H, A), Map.of());
        var timing = new WeaponCharge.Timing(SECOND, SECOND, Optional.empty());
        var states = Map.of(
                "source", empty().withSource(source(H, ORIGIN)),
                "equipment", empty().withEquipment(H, new Loadout(Map.of("test:slot", GEAR), Optional.of("test:slot"))),
                "abilities", empty().withAbilities(H, new AbilityLoadout(Map.of("test:slot", "test:ability"))),
                "actor", empty().withActor(H, Optional.of(new ActorBinding("test:actor"))),
                "reload", empty().withReload(new WeaponReload.Plan(H, "reload", GEAR, ORIGIN, 0, 1_000_000, SECOND, SECOND, Optional.empty())),
                "handling", empty().withHandling(new WeaponHandling.Plan(H, "handling", Optional.empty(), Optional.empty(), "test:slot", GEAR, ORIGIN,
                        WeaponHandling.Phase.STOW, 0, 1_000_000, SECOND, SECOND, Optional.empty(), false)),
                "aim", empty().withAim(new WeaponAim.Plan(H, "aim", "test:slot", GEAR, ORIGIN, WeaponAim.Phase.ENTERING, 0, 1_000_000, SECOND, SECOND, Optional.empty())),
                "charge", empty().withCharge(new WeaponCharge.Plan(H, "charge", "test:slot", GEAR, new BuffInstance.Origin(H, "charge/charge", WEAPON, ""),
                        WeaponCharge.Phase.CHARGING, 0, 1_000_000, timing, timing, Optional.empty())));
        states.forEach((name, state) -> {
            assertDoesNotThrow(() -> validate(directory, Map.of(A, state, B, empty())), name);
            assertThrows(IllegalStateException.class, () -> validate(directory, Map.of(A, empty(), B, state)), name);
            assertThrows(IllegalStateException.class, () -> validate(directory, Map.of(A, state, B, state)), name + " duplicate");
        });
    }

    @Test void buffPlacementUsesHolderRatherThanSourceCreditAndCanDifferFromCharacterPlacement() throws Exception {
        var otherCredit = new BuffInstance.Origin(OTHER, "foreign-credit", WEAPON, "");
        var character = empty().withSource(source(H, otherCredit)); var buffs = buff(H, otherCredit);
        var directory = adopt(Directory.EMPTY, Map.of(H, A), Map.of(H, B), Map.of(A, character, B, buffs, C, empty()));
        assertDoesNotThrow(() -> validate(directory, Map.of(A, character, B, buffs, C, empty().withSource(source(OTHER, ORIGIN)))));
        assertThrows(IllegalStateException.class, () -> validate(directory, Map.of(A, character.withBuffs(buffs.buffs()), B, empty(), C, empty())));
        assertThrows(IllegalStateException.class, () -> validate(directory, Map.of(A, character, B, buffs, C, buffs)));
    }

    @Test void gearBuffPlacementIsExplicitAndIsNotGuessedFromTheCharacterLoadout() throws Exception {
        var character = empty().withEquipment(H, new Loadout(Map.of("test:slot", GEAR), Optional.of("test:slot")));
        var weaponBuff = buff(WEAPON, ORIGIN);
        var directory = adopt(Directory.EMPTY, Map.of(H, A), Map.of(WEAPON, B), Map.of(A, character, B, weaponBuff));
        assertThrows(IllegalArgumentException.class, () -> directory.owner(Facet.BUFFS, H));
        assertEquals(B, directory.owner(Facet.BUFFS, WEAPON).domain());
        assertThrows(IllegalStateException.class, () -> validate(directory, Map.of(A, character.withBuffs(weaponBuff.buffs()), B, empty())));
    }

    @Test void resourcesAmmunitionAndHistoricalShotHoldersDoNotCreateCharacterOwnership() {
        var ammo = new AmmoState(WEAPON, 2, 6, Optional.empty());
        var shot = new WeaponFire.Shot(H, "shot", GEAR, new BuffInstance.Origin(H, "shot/shot", WEAPON, ""), 0, 1_000_000,
                SECOND, SECOND, Optional.empty(), Ammunition.spend(ammo, Ammunition.Pool.MAGAZINE, 1));
        var otherDomain = empty().withAmmo(ammo).withShot(shot).withResource(new ResourceState(new ResourceState.Key(H, "test:energy"), 1, 1, 0));
        var directory = adopt(Directory.EMPTY, Map.of(H, A), Map.of(H, A), Map.of(A, empty(), B, otherDomain));
        assertEquals(A, directory.owner(Facet.CHARACTER, H).domain()); assertEquals(A, directory.owner(Facet.BUFFS, H).domain());
    }

    @Test void transfersInvalidateOldLeasesAcrossRoundTripsWithoutMovingTheOtherFacet() {
        var initial = enroll(Directory.EMPTY, Map.of(H, A), Map.of(H, C));
        var originalCharacter = initial.owner(Facet.CHARACTER, H); var originalBuffs = initial.owner(Facet.BUFFS, H);
        var moved = transfer(initial, Facet.CHARACTER, H, originalCharacter, B);
        assertEquals(B, moved.owner(Facet.CHARACTER, H).domain()); assertEquals(originalBuffs, moved.owner(Facet.BUFFS, H));
        var returned = transfer(moved, Facet.CHARACTER, H, moved.owner(Facet.CHARACTER, H), A);
        assertEquals(A, returned.owner(Facet.CHARACTER, H).domain()); assertNotEquals(originalCharacter, returned.owner(Facet.CHARACTER, H));
        assertThrows(IllegalStateException.class, () -> transfer(returned, Facet.CHARACTER, H, originalCharacter, B));
        assertThrows(IllegalArgumentException.class, () -> transfer(returned, Facet.BUFFS, H, originalBuffs, C));
        var buffsMoved = transfer(returned, Facet.BUFFS, H, originalBuffs, B);
        assertEquals(returned.characters(), buffsMoved.characters()); assertEquals(B, buffsMoved.owner(Facet.BUFFS, H).domain());
        assertEquals(A, initial.owner(Facet.CHARACTER, H).domain()); assertEquals(C, initial.owner(Facet.BUFFS, H).domain());
    }

    @Test void aNewLeaseNeedsMatchingStateMovementBeforePublication() {
        var source = empty().withSource(source(H, ORIGIN));
        var initial = adopt(Directory.EMPTY, Map.of(H, A), Map.of(), Map.of(A, source, B, empty()));
        var moved = transfer(initial, Facet.CHARACTER, H, initial.owner(Facet.CHARACTER, H), B);
        assertThrows(IllegalStateException.class, () -> validate(moved, Map.of(A, source, B, empty())));
        assertDoesNotThrow(() -> validate(moved, Map.of(A, empty(), B, source)));
    }

    @Test void directoryCopiesInputsAndRejectsMalformedOrReusedGenerations() {
        var input = new HashMap<String, Lease>(); input.put(H, new Lease(A, 1));
        var directory = new Directory(2, input, Map.of()); input.clear();
        assertEquals(new Lease(A, 1), directory.owner(Facet.CHARACTER, H));
        assertThrows(UnsupportedOperationException.class, () -> directory.characters().clear());
        assertThrows(IllegalArgumentException.class, () -> new Lease("bad domain", 1));
        assertThrows(IllegalArgumentException.class, () -> new Lease(A, 0));
        assertThrows(IllegalArgumentException.class, () -> new Directory(1, Map.of(H, new Lease(A, 1)), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Directory(3, Map.of(H, new Lease(A, 1)), Map.of(H, new Lease(B, 1))));
        assertThrows(IllegalArgumentException.class, () -> new Directory(2, Map.of(" ", new Lease(A, 1)), Map.of()));
        var full = new Directory(Long.MAX_VALUE, Map.of(), Map.of());
        assertThrows(ArithmeticException.class, () -> enroll(full, Map.of(H, A), Map.of()));
        assertSame(full, enroll(full, Map.of(), Map.of()));
    }

    @Test void batchGenerationAssignmentIsDeterministicAcrossInputMapOrder() {
        var left = new LinkedHashMap<String, String>(); left.put("z", C); left.put("a", A);
        var right = new LinkedHashMap<String, String>(); right.put("a", A); right.put("z", C);
        assertEquals(enroll(Directory.EMPTY, left, Map.of(H, B)), enroll(Directory.EMPTY, right, Map.of(H, B)));
    }
}
