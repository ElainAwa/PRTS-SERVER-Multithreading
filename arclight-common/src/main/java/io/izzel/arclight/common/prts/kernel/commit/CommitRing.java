/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.concurrent.atomic.AtomicLong;

/** One (world, domain) ring buffer. The capacity is declared when the ring is made: a ring that is
 * full refuses the entry and counts the refusal instead of growing, and the oldest entry is only
 * overwritten after it was read.
 *
 * <p>Every write into a ring, every read out of it and every clearing of it needs a permission the
 * log mints ({@link CommitLog.RingAuthority}). The permission names the log key, the log lifetime,
 * this ring, the pair and the reservation it belongs to, and a fill spends it, so a second fill, a
 * permission of another ring of the same pair, one of another log, one of an earlier lifecycle or a
 * null one is refused and counted. A permission cannot be built from outside the log: its type is
 * package private, its constructor is private and it has to carry the log key, which no one else
 * holds. */
public final class CommitRing {

    /** What one push did. */
    public record Push(boolean stored, RejectCode code, int depth) {
    }

    /** A read-only snapshot of one ring, for a reader outside the log. */
    public record Reading(long ringId, String worldId, String domainId, int depth, int capacity,
                          long pushed, long popped, long refusedFull, long permissionRefusals) {
    }

    private static final AtomicLong RING_IDS = new AtomicLong(1L);

    private final long ringId = RING_IDS.getAndIncrement();
    private final long logLifetime;
    private final Object logKey;
    private final String worldId;
    private final String domainId;
    private final CommitLog.Entry[] slots;
    private final int capacity;
    private int head;
    private int size;
    private long pushed;
    private long popped;
    private long refusedFull;
    private long permissionRefusals;

    CommitRing(String worldId, String domainId, int capacity, long logLifetime, Object logKey) {
        this.worldId = worldId;
        this.domainId = domainId;
        this.logLifetime = logLifetime;
        this.logKey = logKey;
        this.capacity = Math.max(1, capacity);
        this.slots = new CommitLog.Entry[this.capacity];
    }

    /** Stores one entry at the tail, under a permission to fill this ring. */
    CommitRing.Push push(CommitLog.Entry entry, CommitLog.RingAuthority authority) {
        if (entry == null) {
            permissionRefusals++;
            return new Push(false, RejectCode.COUNTER_MISSING, size);
        }
        if (!allows(authority, CommitLog.RingAuthority.Kind.FILL)) {
            permissionRefusals++;
            return new Push(false, RejectCode.COMMIT_ORDER_VIOLATION, size);
        }
        if (size >= capacity) {
            refusedFull++;
            return new Push(false, RejectCode.QUEUE_CAP_EXCEEDED, size);
        }
        if (!authority.spend()) {
            permissionRefusals++;
            return new Push(false, RejectCode.COMMIT_ORDER_VIOLATION, size);
        }
        slots[(head + size) % capacity] = entry;
        size++;
        pushed++;
        return new Push(true, null, size);
    }

    /** The entry the static traversal reads next. */
    public CommitLog.Entry peek() {
        return size == 0 ? null : slots[head];
    }

    /** Takes the entry the traversal read, under a permission to read this ring. */
    CommitLog.Entry pop(CommitLog.RingAuthority authority) {
        if (!allows(authority, CommitLog.RingAuthority.Kind.READ)) {
            permissionRefusals++;
            return null;
        }
        if (size == 0) {
            return null;
        }
        CommitLog.Entry entry = slots[head];
        slots[head] = null;
        head = (head + 1) % capacity;
        size--;
        popped++;
        return entry;
    }

    /** Empties the ring, under a permission to clear this ring. */
    void reset(CommitLog.RingAuthority authority) {
        if (!allows(authority, CommitLog.RingAuthority.Kind.CLEAR)) {
            permissionRefusals++;
            return;
        }
        for (int index = 0; index < capacity; index++) {
            slots[index] = null;
        }
        head = 0;
        size = 0;
        pushed = 0L;
        popped = 0L;
        refusedFull = 0L;
    }

    private boolean allows(CommitLog.RingAuthority authority, CommitLog.RingAuthority.Kind kind) {
        return authority != null && authority.key() == logKey && authority.kind() == kind
            && authority.logLifetime() == logLifetime && authority.ringId() == ringId
            && worldId.equals(authority.worldId()) && domainId.equals(authority.domainId());
    }

    /** What this ring holds, without a way to change it. */
    public Reading reading() {
        return new Reading(ringId, worldId, domainId, size, capacity, pushed, popped, refusedFull,
            permissionRefusals);
    }

    public long ringId() {
        return ringId;
    }

    public String worldId() {
        return worldId;
    }

    public String domainId() {
        return domainId;
    }

    public String key() {
        return worldId + "/" + domainId;
    }

    public int depth() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    public long pushed() {
        return pushed;
    }

    public long popped() {
        return popped;
    }

    public long refusedFull() {
        return refusedFull;
    }

    void noteRefusedFull() {
        refusedFull++;
    }

    /** Fills, reads or clearings this ring refused because the permission was not one of its own. */
    public long permissionRefusals() {
        return permissionRefusals;
    }
}
