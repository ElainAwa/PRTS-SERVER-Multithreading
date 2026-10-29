/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

/** The offline stability window over the region identity: one hash per read, how many reads in a
 * row kept it, and the exact boundary at which a merge or split may be adopted. It reads no
 * server and is not wired into the observer's read path; the observer still only names the
 * partition and publishes the declared window. */
public final class RegionStability {

    /** The identity as last seen, the run of equal reads and the tick the run started on. */
    public record Decision(boolean available, boolean changed, String identityHash, long stableTicks,
                           long sinceTick, long observedTick) {
    }

    private String identityHash = "-";
    private long stableTicks;
    private long sinceTick = -1L;
    private long changes;
    private boolean observed;

    /** Observes one partition digest. An absent digest clears the window; a different digest starts
     * a new one; the first observation is a baseline and does not count as a change. */
    public synchronized Decision observe(String hash, long tick) {
        if (hash == null || hash.isBlank() || "-".equals(hash)) {
            reset();
            return new Decision(false, false, "-", 0L, -1L, tick);
        }
        boolean changed = observed && !hash.equals(identityHash);
        if (!observed || changed) {
            if (changed) {
                changes++;
            }
            identityHash = hash;
            sinceTick = tick;
            stableTicks = 1L;
            observed = true;
        } else {
            stableTicks++;
        }
        return new Decision(true, changed, identityHash, stableTicks, sinceTick, tick);
    }

    /** True when the current run has reached the window; a blank/absent run is never stable. */
    public synchronized boolean stable(int windowTicks) {
        return observed && stableTicks >= Math.max(1, windowTicks);
    }

    public synchronized long stableTicks() {
        return stableTicks;
    }

    public synchronized long changes() {
        return changes;
    }

    public synchronized String identityHash() {
        return identityHash;
    }

    public synchronized boolean available() {
        return observed;
    }

    /** The declared rollback gate is the same value the observer publishes. */
    public int rollbackGateTicks() {
        return RegionIdentityObserver.ROLLBACK_GATE_TICKS;
    }

    public synchronized void reset() {
        identityHash = "-";
        stableTicks = 0L;
        sinceTick = -1L;
        changes = 0L;
        observed = false;
    }
}
