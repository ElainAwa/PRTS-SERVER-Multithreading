/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The values of the state hash, pinned. Every constant here was read from the implementation that
 * folded the frame row by row through a map, and this test is what keeps the array fold of the same
 * frame bit for bit identical: the running value, the per-world, per-region, per-batch and
 * per-field digests, and the per-entity view that is now rebuilt from the row sidecar.
 */
class StateHashGoldenTest {

    private static final long ONE_ROW = 0xad19361e86a552b4L;
    private static final long TWO_ROWS_UNSORTED = 0x72340a3d6423abcdL;
    private static final long THREE_WORLDS = 0x06e023146973f187L;
    private static final long DUPLICATE_KEY = 0xe31da4e1be867098L;
    private static final long EDGE_DOUBLES = 0x55c0fd949d6b807fL;
    private static final long QUANTIZED_EIGHT = 0x4f21150c46fda8f2L;

    @Test
    void oneRowKeepsItsValueAndItsRowIndex() {
        DomainHash hash = StateHasher.hash("entity", 7L, List.of(
            slice("world", "r0.0", 1L, 5L, 1.0, 64.0, 2.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L)),
            HashWhitelist.bitexact());
        assertEquals(ONE_ROW, hash.value());
        assertEquals(StateHasher.ALGORITHM_ID, hash.algorithmId());
        assertEquals(Map.of("world|r0.0|1|5", 0x302772346299bcfbL), hash.entityDigests());
        assertEquals(0x576c89be848c5c16L, hash.fieldDigests().get("position"));
        assertEquals(StateHasher.ROW_INDEX_ID, hash.rows().layoutId());
        assertEquals(1, hash.rows().size());
        assertEquals(5L, hash.rows().entitySeq(0));
        assertEquals("world", hash.rows().slice(0).worldId());
        assertNotEquals(0L, hash.segmentHeaderDigest());
    }

    @Test
    void theFoldDoesNotDependOnTheOrderRowsArriveIn() {
        DomainHash hash = StateHasher.hash("entity", 7L, List.of(
            slice("world", "r0.0", 1L, 2L, 3.5, 64.0, 4.5, 20.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L),
            slice("world", "r0.0", 1L, 1L, 1.5, 64.0, 2.5, 10.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L)),
            HashWhitelist.bitexact());
        assertEquals(TWO_ROWS_UNSORTED, hash.value());
        assertEquals(1L, hash.rows().entitySeq(0), "the row index is the fold order");
        assertEquals(2L, hash.rows().entitySeq(1));
    }

    @Test
    void theGroupAndEntityDigestsKeepTheirKeysAndValues() {
        DomainHash hash = StateHasher.hash("entity", 1200L, List.of(
            slice("world-a", "r0.0", 1L, 1L, 1.0, 64.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L),
            slice("world-a", "r1.0", 2L, 1L, 2.0, 64.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 1L),
            slice("world-b", "r0.0", 3L, 1L, 3.0, 64.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 2L),
            slice("world-b", "r0.0", 3L, 2L, 4.0, 64.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1L, 7L, 2L)),
            HashWhitelist.bitexact());
        assertEquals(THREE_WORLDS, hash.value());
        assertEquals(Map.of(
            "world-a|r0.0|1|1", 0x3cdd679c37f7dc19L,
            "world-a|r1.0|2|1", 0xdd753919f8afb8d9L,
            "world-b|r0.0|3|1", 0xa0268e1707c9fdc0L,
            "world-b|r0.0|3|2", 0x65a954b5fcad7b57L), hash.entityDigests());
        assertEquals(2, hash.worldDigests().size());
        assertEquals(3, hash.regionDigests().size());
        assertEquals(3, hash.batchDigests().size());
        assertEquals(4, hash.rows().size());
    }

    @Test
    void aRepeatedEntityKeyKeepsTheLastRowAsItAlwaysDid() {
        DomainHash hash = StateHasher.hash("entity", 9L, List.of(
            slice("world", "r0.0", 1L, 5L, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L),
            slice("world", "r0.0", 1L, 5L, 9.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L)),
            HashWhitelist.bitexact());
        assertEquals(DUPLICATE_KEY, hash.value());
        assertEquals(Map.of("world|r0.0|1|5", 0x60f172346299bcfbL), hash.entityDigests(),
            "the per-entity view collapses a repeated key to its last row");
        assertEquals(2, hash.rows().size(), "the row index keeps both rows");
    }

    @Test
    void theEdgeValuesOfADoubleFoldTheSameWay() {
        DomainHash hash = StateHasher.hash("entity", 11L, List.of(
            slice("world", "r0.0", 1L, 1L, -0.0, Double.NaN, Double.POSITIVE_INFINITY, 0.0,
                Double.NEGATIVE_INFINITY, Double.MIN_VALUE, Math.nextUp(1.0), -1.0e-300, 3L,
                Long.MAX_VALUE, Long.MIN_VALUE)), HashWhitelist.bitexact());
        assertEquals(EDGE_DOUBLES, hash.value());
    }

    @Test
    void theQuantizedFormKeepsItsValueAndItsIdentifier() {
        DomainHash hash = StateHasher.hash("entity", 13L, List.of(
            slice("world", "r0.0", 1L, 1L, 1.0, 64.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L),
            slice("world", "r0.0", 1L, 2L, Math.nextUp(1.0), 64.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0L, 0L, 0L)), HashWhitelist.quantized(8));
        assertEquals(QUANTIZED_EIGHT, hash.value());
        assertEquals(StateHasher.QUANTIZED_ALGORITHM_ID, hash.algorithmId());
    }

    @Test
    void aFrameWithoutRowsIsRefusedAndCarriesNoIndex() {
        DomainHash empty = StateHasher.hash("entity", 7L, List.of(), HashWhitelist.bitexact());
        assertFalse(empty.available());
        assertEquals(DomainHash.Failure.EMPTY, empty.failure());
        assertEquals(0, empty.rows().size());
        assertFalse(empty.rows().located());
        DomainHash crossed = StateHasher.hash("entity", 7L,
            List.of(slice("world", "r0.0", 1L, 1L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L,
                0L)), null);
        assertEquals(DomainHash.Failure.CROSSED, crossed.failure());
        assertTrue(crossed.entityDigests().isEmpty());
    }

    private static StateHasher.Slice slice(String world, String region, long batch, long seq,
                                           double x, double y, double z, double yaw, double pitch,
                                           double velX, double velY, double velZ, long flags,
                                           long slotGeneration, long segmentRef) {
        return new StateHasher.Slice(world, region, batch, seq, x, y, z, yaw, pitch, velX, velY,
            velZ, flags, slotGeneration, segmentRef);
    }
}
