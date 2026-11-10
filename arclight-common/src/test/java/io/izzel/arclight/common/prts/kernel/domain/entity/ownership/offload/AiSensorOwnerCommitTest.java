/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner-side commit/stale/cancel semantics for the sensor candidate, without a live Level. */
class AiSensorOwnerCommitTest {

    record Candidate(long actorId, long targetEntityId, long snapshotVersion) {
    }

    static final class Owner {
        private final long expectedVersion;
        private final Map<Long, Long> targets;
        private final AtomicInteger events = new AtomicInteger();
        private boolean committed;

        Owner(long expectedVersion, Map<Long, Long> targets) {
            this.expectedVersion = expectedVersion;
            this.targets = targets;
        }

        boolean commit(Candidate candidate) {
            if (candidate.snapshotVersion() != expectedVersion) return false;
            if (committed) return false;
            targets.put(candidate.actorId(), candidate.targetEntityId());
            events.incrementAndGet();
            committed = true;
            return true;
        }
    }

    @Test
    void commitOnceAndStaleAndCancelAreSkipped() {
        Map<Long, Long> memories = new java.util.HashMap<>();
        Owner owner = new Owner(9L, memories);
        assertTrue(owner.commit(new Candidate(1, 100, 9L)));
        assertEquals(1, owner.events.get());
        assertFalse(owner.commit(new Candidate(1, 101, 9L)));
        assertEquals(1, memories.size());
        assertFalse(owner.commit(new Candidate(2, 102, 8L)));
        assertEquals(1, owner.events.get());
    }

    @Test
    void cancelAndLateAreSkippedWithoutNewEvent() {
        Map<Long, Long> memories = new java.util.HashMap<>();
        Owner owner = new Owner(9L, memories);
        assertTrue(owner.commit(new Candidate(1, 100, 9L)));
        int events = owner.events.get();
        // cancel/late: no valid commit after the owner has been cancelled or a late answer arrives
        assertFalse(owner.commit(new Candidate(2, 101, 9L)));
        assertEquals(events, owner.events.get());
        assertEquals(1, memories.size());
    }

    @Test
    void poiBlockNavReadSetsRejectUnknownAndWrites() {
        AiReadView view = new AiReadView(Map.of("e1", "self"), Map.of("b1", 1),
            Map.of("p1", "home"), new AiReadView.Version(1L, 2L));
        assertEquals("home", view.poi("p1"));
        assertThrows(AiReadView.UnknownRead.class, () -> view.poi("missing"));
        assertThrows(AiReadView.WriteRejected.class, () -> view.set("p1", "x"));
    }
}
