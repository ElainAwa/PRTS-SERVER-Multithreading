/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** What a wait point does when it crosses its upper bound, and the run of clean ticks that lets it
 * back. No action is executed in this build, so a reached count must never be read as one that ran. */
public final class ForcedConvergence {

    /** The window the readout answers against; a declared value, not a measured one. */
    public static final int ROLLBACK_WINDOW_TICKS = 3;

    /** The action one crossed wait declares, where it degrades to, and the refusal code it carries. */
    public record Verdict(String wpId, String action, String degradeTo, String code, boolean reached,
                          boolean effective) {
    }

    /** The current run of clean ticks against the window; a tick that carried a crossed wait ends it. */
    public record Rollback(int windowTicks, int ticksObserved, int ticksClean, boolean ready) {
    }

    private final AtomicLong reached = new AtomicLong();
    private final AtomicLong effective = new AtomicLong();
    private final AtomicInteger ticksObserved = new AtomicInteger();
    private final AtomicInteger ticksClean = new AtomicInteger();
    private final Map<String, AtomicLong> reachedByRow = new ConcurrentHashMap<>();

    /** Records a wait that crossed its bound and answers what its row declares. */
    public Verdict noteReached(String wpId, String action, String degradeTo) {
        reached.incrementAndGet();
        if (wpId != null) {
            reachedByRow.computeIfAbsent(wpId, ignored -> new AtomicLong()).incrementAndGet();
        }
        return new Verdict(wpId, action, degradeTo, RejectTrigger.WAIT_BOUND_OVERRUN.code().text(),
            true, false);
    }

    /** Counts one action that ran. No caller in this build: the seam is here so the published
     * effective count can only move once an action is implemented. */
    public void noteEffective() {
        effective.incrementAndGet();
    }

    public void noteTick(boolean crossed) {
        ticksObserved.incrementAndGet();
        if (crossed) {
            ticksClean.set(0);
        } else {
            ticksClean.incrementAndGet();
        }
    }

    public Rollback rollback(int windowTicks) {
        int window = Math.max(1, windowTicks);
        return new Rollback(window, ticksObserved.get(), ticksClean.get(),
            ticksClean.get() >= window);
    }

    public long reached() {
        return reached.get();
    }

    public long reachedOf(String wpId) {
        AtomicLong count = wpId == null ? null : reachedByRow.get(wpId);
        return count == null ? 0L : count.get();
    }

    public long effective() {
        return effective.get();
    }

    public void reset() {
        reached.set(0L);
        effective.set(0L);
        ticksObserved.set(0);
        ticksClean.set(0);
        reachedByRow.clear();
    }
}
