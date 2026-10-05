/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

/** One (world, domain) ring buffer. The capacity is declared when the ring is made: a ring that is
 * full refuses the entry and counts the refusal instead of growing, and the oldest entry is only
 * overwritten after it was read. */
public final class CommitRing {

    /** What one push did. */
    public record Push(boolean stored, RejectCode code, int depth) {
    }

    private final String worldId;
    private final String domainId;
    private final CommitLog.Entry[] slots;
    private final int capacity;
    private int head;
    private int size;
    private long pushed;
    private long popped;
    private long refusedFull;

    public CommitRing(String worldId, String domainId, int capacity) {
        this.worldId = worldId;
        this.domainId = domainId;
        this.capacity = Math.max(1, capacity);
        this.slots = new CommitLog.Entry[this.capacity];
    }

    /** Stores one entry at the tail. */
    public Push push(CommitLog.Entry entry) {
        if (size >= capacity) {
            refusedFull++;
            return new Push(false, RejectCode.QUEUE_CAP_EXCEEDED, size);
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

    /** Takes the entry the traversal read. */
    public CommitLog.Entry pop() {
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

    public void reset() {
        for (int index = 0; index < capacity; index++) {
            slots[index] = null;
        }
        head = 0;
        size = 0;
        pushed = 0L;
        popped = 0L;
        refusedFull = 0L;
    }
}
