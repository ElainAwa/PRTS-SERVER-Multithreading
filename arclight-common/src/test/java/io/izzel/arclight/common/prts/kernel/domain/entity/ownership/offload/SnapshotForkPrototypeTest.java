/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotForkPrototypeTest {

    private static FrozenReadSet readSet() {
        return new FrozenReadSet(
            Map.of(1L, new FrozenReadSet.EntityState(10, 20, 1.0, 2.0, 3.0, 0.0, 0.0, 0.0)),
            Map.of(9L, 42), Map.of(9L, "home"), 0x1234L);
    }

    @Test
    void unknownReadAndEveryWriteFail() {
        FrozenReadSet set = readSet();
        assertEquals(42, set.block(9L));
        assertThrows(FrozenReadSet.UnknownRead.class, () -> set.block(8L));
        assertThrows(FrozenReadSet.WriteRejected.class, () -> set.setBlock(9L, 1));
        assertThrows(FrozenReadSet.WriteRejected.class,
            () -> set.addEntity(2L, new FrozenReadSet.EntityState(0, 0, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    void cloneIsIsolatedFromSource() {
        SnapshotFork.EntityState source = new SnapshotFork.EntityState(1, 10, 20, 7, 1, 2, 3, 0, 0, 0);
        SnapshotFork fork = new SnapshotFork(source, readSet(), 5L);
        SnapshotFork.EntityState clone = fork.clone();
        assertEquals(1, clone.x());
        assertEquals(7, clone.tickCount());
        fork.source();
        // The clone record is a separate immutable value; no live Entity reference exists.
        assertTrue(fork.clone() != source);
    }

    @Test
    void commitAppliesOnceAndFallbackDoesNotApply() {
        SnapshotFork.EntityState source = new SnapshotFork.EntityState(1, 10, 20, 7, 1, 2, 3, 0, 0, 0);
        SnapshotFork fork = new SnapshotFork(source, readSet(), 5L);
        SnapshotFork.EntityState answer = new SnapshotFork.EntityState(1, 10, 20, 8, 1.5, 2, 3.1, 0.1, 0, 0);
        AtomicInteger applies = new AtomicInteger();
        SnapshotFork.Commit ok = fork.hostCommit(answer, 0x1234L, 5L, a -> applies.incrementAndGet());
        assertTrue(ok.committed());
        assertEquals(1, applies.get());
        SnapshotFork.Commit fallback = fork.hostCommit(answer, 0x9999L, 5L, a -> applies.incrementAndGet());
        assertTrue(!fallback.committed());
        assertEquals(1, applies.get());
    }
}
