/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The generation counter of one slot or one segment.
 *
 * <p>The counter only moves forward, and it always moves before a slot is handed to another owner.
 * A stale reference is then refused by comparison rather than by trusting the caller's timing.</p>
 */
public final class SlotGeneration {

    private final AtomicLong value;

    /** Creates a counter at generation zero. */
    public SlotGeneration() {
        this(0L);
    }

    /** @param initial the generation to start at */
    public SlotGeneration(long initial) {
        this.value = new AtomicLong(initial);
    }

    /** @return the current generation */
    public long value() {
        return value.get();
    }

    /**
     * Advances the generation.
     *
     * @return the new generation
     */
    public long bump() {
        return value.incrementAndGet();
    }
}
