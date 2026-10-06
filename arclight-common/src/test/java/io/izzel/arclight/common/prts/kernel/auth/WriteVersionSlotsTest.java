/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The version of a write right domain: what a grant mints, what a later grant keeps and what an
 * invalidation retires. */
class WriteVersionSlotsTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String OTHER = "minecraft:the_nether";
    private static final String DOMAIN = "block_write";

    @Test
    void theFirstGrantMintsAValueAndALaterOneKeepsIt() {
        WriteVersionSlots slots = new WriteVersionSlots();
        slots.refresh(true);

        long first = slots.grant(WORLD, WriteLevel.REGION, DOMAIN);
        assertTrue(first > WriteVersionSlots.NOT_CARRIED, "a granted slot carries a positive version");
        assertEquals(first, slots.grant(WORLD, WriteLevel.REGION, DOMAIN));
        assertEquals(first, slots.carried(WORLD, WriteLevel.REGION, DOMAIN));
        assertEquals(1L, slots.grantedCount());
        assertEquals(1, slots.activeSlots());
    }

    @Test
    void anAdvancedSlotRetiresTheValueItCarriedAndNeverHandsItOutAgain() {
        WriteVersionSlots slots = new WriteVersionSlots();
        slots.refresh(true);
        long before = slots.grant(WORLD, WriteLevel.REGION, DOMAIN);

        long after = slots.advance(WORLD, WriteLevel.REGION, DOMAIN);
        assertNotEquals(before, after, "the old value is retired");
        assertEquals(after, slots.carried(WORLD, WriteLevel.REGION, DOMAIN));
        assertEquals(1L, slots.grantedCount());
        assertEquals(1L, slots.advancedCount());

        long next = slots.advance(WORLD, WriteLevel.REGION, DOMAIN);
        assertNotEquals(after, next);
        assertNotEquals(before, next, "no value is ever minted twice");
    }

    @Test
    void aWorldThatLeavesTheLiveSetHasItsSlotsRetiredAndReclaimed() {
        WriteVersionSlots slots = new WriteVersionSlots();
        slots.refresh(true);
        long ofOther = slots.grant(OTHER, WriteLevel.REGION, DOMAIN);
        slots.grant(WORLD, WriteLevel.REGION, DOMAIN);
        assertEquals(0, slots.noteWorlds(List.of(WORLD, OTHER)));

        assertEquals(1, slots.noteWorlds(List.of(WORLD)), "the world that left is reclaimed");
        assertEquals(WriteVersionSlots.NOT_CARRIED, slots.carried(OTHER, WriteLevel.REGION, DOMAIN),
            "a write frozen against the old generation finds nothing");
        assertEquals(1L, slots.worldDropCount());
        assertEquals(1L, slots.reclaimedCount());
        assertEquals(1, slots.activeSlots(), "the world that stayed keeps its slot");

        assertEquals(0, slots.noteWorlds(List.of(WORLD, OTHER)),
            "the world is only tracked again; its slots were already reclaimed");
        long renewed = slots.grant(OTHER, WriteLevel.REGION, DOMAIN);
        assertNotEquals(ofOther, renewed, "a world that appears again is a new generation");
    }

    @Test
    void aDisabledSourceGrantsNothingAndCarriesNothing() {
        WriteVersionSlots slots = new WriteVersionSlots();

        assertEquals(WriteVersionSlots.NOT_CARRIED, slots.grant(WORLD, WriteLevel.REGION, DOMAIN));
        assertEquals(WriteVersionSlots.NOT_CARRIED, slots.carried(WORLD, WriteLevel.REGION, DOMAIN));
        assertEquals(0, slots.noteWorlds(List.of(WORLD)));
        assertEquals(0, slots.activeSlots());
        assertEquals(0L, slots.grantedCount());
        assertEquals(0L, slots.notCarriedCount(), "a source that is off counts no lookup");
    }

    @Test
    void aResetDropsTheSlotsAndStillRefusesToReuseAValue() {
        WriteVersionSlots slots = new WriteVersionSlots();
        slots.refresh(true);
        long before = slots.grant(WORLD, WriteLevel.REGION, DOMAIN);

        slots.reset();
        assertEquals(0, slots.activeSlots());
        assertEquals(WriteVersionSlots.NOT_CARRIED, slots.carried(WORLD, WriteLevel.REGION, DOMAIN));

        long after = slots.grant(WORLD, WriteLevel.REGION, DOMAIN);
        assertNotEquals(before, after);
    }

    @Test
    void anEnabledSlotCountsItsMisses() {
        WriteVersionSlots slots = new WriteVersionSlots();
        slots.refresh(true);

        assertEquals(WriteVersionSlots.NOT_CARRIED, slots.carried(WORLD, WriteLevel.REGION, DOMAIN));
        assertEquals(1L, slots.notCarriedCount());
        slots.grant(WORLD, WriteLevel.REGION, DOMAIN);
        slots.carried(WORLD, WriteLevel.REGION, DOMAIN);
        assertEquals(1L, slots.notCarriedCount());
        assertEquals(1L, slots.carriedCount());
    }
}
