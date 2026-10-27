/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What may fill, read or clear a ring: only the log, only for one ring of one pair, and only once per
 * admission. The permission is minted inside the log and spent by the push that follows it in the same
 * call, so no code outside the log can hold one at all: the cases below drive the real paths and read
 * the ring they left behind. */
class CommitRingPermissionTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";

    private static CommitLog log(int capacity) {
        return new CommitLog(() -> capacity,
            (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false),
            new EffectStore(4096, 8));
    }

    private static CommitLog.Batch batch() {
        return new CommitLog.Batch(WORLD, DOMAIN, "a/0", CommitLog.Batch.Kind.APPLY, 7L, 4242L, 2);
    }

    private static CommitLog.Entry entry() {
        return new CommitLog.Entry(1L, 1L, 7L, 0, WORLD, DOMAIN, "a/0", 0L,
            CommitLog.Batch.Kind.APPLY, 7L, 4242L, 2, CommitLog.Entry.Disposition.ACCEPTED, null, false);
    }

    private static CommitRing ringOf(CommitLog log, String worldId, String domainId) {
        for (CommitRing ring : log.rings()) {
            if (ring.worldId().equals(worldId) && ring.domainId().equals(domainId)) {
                return ring;
            }
        }
        throw new IllegalStateException("no ring for " + worldId + "/" + domainId);
    }

    @Test
    void aNullPermissionFillsNothing() {
        CommitLog log = log(2);
        log.beginTick(1L);
        log.reach(batch());
        CommitRing ring = ringOf(log, WORLD, DOMAIN);
        int depth = ring.depth();
        CommitRing.Push push = ring.push(entry(), null);
        assertFalse(push.stored());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, push.code());
        assertEquals(1L, ring.permissionRefusals());
        assertEquals(depth, ring.depth());
    }

    @Test
    void theLogFillsARingOnlyWhereItsOwnChecksAdmittedTheLine() {
        CommitLog log = log(2);
        log.beginTick(1L);
        assertTrue(log.reach(batch()).logged());

        CommitRing ring = ringOf(log, WORLD, DOMAIN);

        assertEquals(1, ring.depth(), "the admitted line is the one entry of the ring");
        assertEquals(1L, ring.pushed());
        assertFalse(ring.push(entry(), null).stored(), "a line nobody admitted takes no entry");
        assertEquals(1, ring.depth());
        assertEquals(1, log.ringDepth());
    }

    @Test
    void aRingWithNoRoomTakesNoEntryFromTheNextLine() {
        CommitLog log = log(1);
        log.beginTick(1L);
        assertTrue(log.reach(batch()).logged());

        CommitLog.Verdict full = log.reach(batch());

        assertFalse(full.logged());
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, full.code());
        assertEquals(1L, log.refusedFull());
        assertEquals(1L, ringOf(log, WORLD, DOMAIN).refusedFull(),
            "the refusal is visible on the ring that had no room");
        assertEquals(1, log.ringDepth(), "the ring still carries the one entry it admitted");
        assertEquals(1, log.ringCapacity());
    }

    @Test
    void aSlotThisLogNoLongerHoldsIsNotASlotOfThisOne() {
        CommitLog log = log(2);
        CommitLog.RingSlot slot = log.reserveSlot(WORLD, DOMAIN);
        assertNotNull(slot);
        assertTrue(log.releaseSlot(slot), "a slot that was given back is not held any more");
        assertFalse(log.releaseSlot(slot), "and giving it back twice moves nothing");
        assertEquals(0, log.reservedSlots());
    }

    @Test
    void readingAndClearingNeedAPermissionToo() {
        CommitLog log = log(2);
        log.beginTick(1L);
        log.reach(batch());
        CommitRing ring = ringOf(log, WORLD, DOMAIN);
        int depth = ring.depth();
        assertNotNull(ring.peek(), "reading what the traversal would read stays open");
        assertNull(ring.pop(null));
        assertEquals(depth, ring.depth(), "an outside reader cannot take a line");
        ring.reset(null);
        assertEquals(depth, ring.depth(), "nor clear the ring");
        assertEquals(2L, ring.permissionRefusals());
        assertEquals(depth, ring.reading().depth(), "the snapshot says what the ring holds");
    }

    @Test
    void aResetLeavesANewRingAndTheOldOneWhereItWas() {
        CommitLog log = log(2);
        log.beginTick(1L);
        log.reach(batch());
        CommitRing before = ringOf(log, WORLD, DOMAIN);
        log.reset();
        log.beginTick(1L);
        log.reach(batch());
        CommitRing after = ringOf(log, WORLD, DOMAIN);

        assertNotSame(before, after, "the reset leaves a new ring for the same pair");
        assertFalse(before.push(entry(), null).stored(), "the old ring takes nothing more");
        assertEquals(1, after.depth());
    }

    @Test
    void theMutatorsAndTheMintAreOutOfReachOfAnotherPackage() throws Exception {
        assertFalse(Modifier.isPublic(CommitRing.class.getDeclaredMethod("push", CommitLog.Entry.class,
            CommitLog.RingAuthority.class).getModifiers()));
        assertFalse(Modifier.isPublic(CommitRing.class.getDeclaredMethod("pop",
            CommitLog.RingAuthority.class).getModifiers()));
        assertFalse(Modifier.isPublic(CommitRing.class.getDeclaredMethod("reset",
            CommitLog.RingAuthority.class).getModifiers()));
        assertFalse(Modifier.isPublic(CommitRing.class.getDeclaredConstructor(String.class,
            String.class, int.class, long.class, Object.class).getModifiers()));
        assertTrue(Modifier.isPrivate(CommitLog.class.getDeclaredMethod("fill", CommitRing.class,
            long.class).getModifiers()), "the mint is the log's own and no package can ask for one");
        assertTrue(Modifier.isPrivate(CommitLog.class.getDeclaredMethod("store", CommitRing.class,
            long.class, CommitLog.Entry.class).getModifiers()),
            "the one place a ring is filled is inside the log");
        for (var constructor : CommitLog.RingAuthority.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()),
                "a permission cannot be built outside the log");
        }
        assertFalse(Modifier.isPublic(CommitLog.RingAuthority.class.getModifiers()));
    }
}
