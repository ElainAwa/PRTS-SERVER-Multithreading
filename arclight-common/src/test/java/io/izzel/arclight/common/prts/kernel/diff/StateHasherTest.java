/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The state hash: the whitelist, the bit-exact fold and the descent that locates a fork. */
class StateHasherTest {

    private static final long TICK = 7L;

    @Test
    void aWhitelistThatIsEmptyOrCrossedIsAnError() {
        assertThrows(IllegalArgumentException.class,
            () -> new HashWhitelist(List.of(), List.of(), false, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new HashWhitelist(List.of("position"), List.of("position"), false, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new HashWhitelist(List.of("secret"), List.of(), false, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new HashWhitelist(List.of("position"), List.of(), true, 0));
        assertFalse(HashWhitelist.bitexact().quantized());
        assertEquals(StateHasher.ALGORITHM_ID, HashWhitelist.bitexact().quantized()
            ? StateHasher.QUANTIZED_ALGORITHM_ID : StateHasher.ALGORITHM_ID);
    }

    @Test
    void theSameRowsHashTheSameWhateverOrderTheyArriveIn() {
        StateHasher.Slice first = slice("world", "r0.0", 1L, 1L, 1.5, 64.0, 2.5, 10.0, 0.0);
        StateHasher.Slice second = slice("world", "r0.0", 1L, 2L, 3.5, 64.0, 4.5, 20.0, 0.0);
        DomainHash ordered = StateHasher.hash("entity", TICK, List.of(first, second),
            HashWhitelist.bitexact());
        DomainHash reversed = StateHasher.hash("entity", TICK, List.of(second, first),
            HashWhitelist.bitexact());
        assertEquals(ordered.value(), reversed.value());
        assertEquals(StateHasher.ALGORITHM_ID, ordered.algorithmId());
        assertTrue(ordered.comparable());
    }

    @Test
    void oneBitOfOneCoordinateChangesTheHash() {
        StateHasher.Slice one = slice("world", "r0.0", 1L, 1L, 1.0, 64.0, 2.0, 0.0, 0.0);
        StateHasher.Slice neighbour = slice("world", "r0.0", 1L, 1L,
            Math.nextUp(1.0), 64.0, 2.0, 0.0, 0.0);
        DomainHash left = StateHasher.hash("entity", TICK, List.of(one), HashWhitelist.bitexact());
        DomainHash right = StateHasher.hash("entity", TICK, List.of(neighbour),
            HashWhitelist.bitexact());
        assertNotEquals(left.value(), right.value());
    }

    @Test
    void theQuantizedFormIsKeptButNotTheDefault() {
        StateHasher.Slice one = slice("world", "r0.0", 1L, 1L, 1.0, 64.0, 2.0, 0.0, 0.0);
        StateHasher.Slice neighbour = slice("world", "r0.0", 1L, 1L,
            Math.nextUp(1.0), 64.0, 2.0, 0.0, 0.0);
        DomainHash quantizedLeft = StateHasher.hash("entity", TICK, List.of(one),
            HashWhitelist.quantized(8));
        DomainHash quantizedRight = StateHasher.hash("entity", TICK, List.of(neighbour),
            HashWhitelist.quantized(8));
        assertEquals(quantizedLeft.value(), quantizedRight.value());
        assertEquals(StateHasher.QUANTIZED_ALGORITHM_ID, quantizedLeft.algorithmId());
    }

    @Test
    void anEmptyRangeIsRefusedInsteadOfHashed() {
        DomainHash empty = StateHasher.hash("entity", TICK, List.of(), HashWhitelist.bitexact());
        assertFalse(empty.available());
        assertEquals(DomainHash.Failure.EMPTY, empty.failure());
        assertFalse(empty.comparable());
    }

    @Test
    void theComparisonDescendsToTheEntityAndTheField() {
        DiffProbe probe = new DiffProbe();
        StateHasher.Slice base = slice("world", "r0.0", 1L, 5L, 1.0, 64.0, 2.0, 10.0, 0.0);
        StateHasher.Slice changed = slice("world", "r0.0", 1L, 5L, 1.0, 64.0, 2.0, 11.0, 0.0);
        DomainHash parallel = StateHasher.hash("entity", TICK, List.of(changed),
            HashWhitelist.bitexact());
        DomainHash serial = StateHasher.hash("entity", TICK, List.of(base),
            HashWhitelist.bitexact());
        probe.compare(parallel, serial);

        DiffReport report = probe.report();
        assertEquals(1L, report.tickPairs());
        assertEquals(0L, report.equal());
        assertEquals(TICK, report.firstForkTick());
        assertEquals("world", report.firstForkWorld());
        assertEquals("r0.0", report.firstForkRegion());
        assertEquals(1L, report.firstForkBatch());
        assertEquals(5L, report.firstForkEntitySeq());
        assertEquals("orientation", report.firstForkField());
        assertTrue(report.forkLine().contains("entity_seq=5"));
        assertEquals(0L, report.unattributed());

        DiffProbe agreeing = new DiffProbe();
        agreeing.compare(parallel, parallel);
        assertEquals(1L, agreeing.report().equal());
        assertEquals(1.0, agreeing.report().rate());
    }

    private static StateHasher.Slice slice(String world, String region, long batch, long seq,
                                           double x, double y, double z, double yaw,
                                           double pitch) {
        return new StateHasher.Slice(world, region, batch, seq, x, y, z, yaw, pitch, 0.0, 0.0,
            0.0, 0L, 0L, 0L);
    }
}
