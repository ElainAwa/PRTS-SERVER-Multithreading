/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import java.util.LinkedHashMap;
import java.util.Map;

/** The offline isolation of a slow region: an over-share region delays only its own writes, never
 * raises a server-wide barrier, and returns through the declared rollback gate (consecutive clean
 * ticks plus a falling backlog). This is an observation structure; it is not wired into the tick
 * and its region identities are supplied by the caller. */
public final class SlowRegionIsolation {

    /** One region's current share, self time and backlog. */
    public record Region(double shareMs, double selfMs, long backlog) {
    }

    /** What one observation decided for one region. */
    public record Decision(String regionId, boolean isolated, boolean writeDeferred,
                           boolean rolledBack, long cleanTicks, boolean overShare,
                           boolean backlogFalling, long barrierWaits) {
    }

    private record State(Region region, boolean isolated, long cleanTicks, long backlogBefore) {
    }

    private final boolean enabled;
    private final int rollbackWindow;
    private final Map<String, State> states = new LinkedHashMap<>();
    private long skipped;
    private long rollbacks;

    /** A disabled isolation is the default: a disabled instance never isolates and never defers. */
    public SlowRegionIsolation() {
        this(false, RegionIdentityObserver.ROLLBACK_GATE_TICKS);
    }

    public SlowRegionIsolation(boolean enabled, int rollbackWindow) {
        this.enabled = enabled;
        this.rollbackWindow = Math.max(1, rollbackWindow);
    }

    /** Observes one region. Only the observed region can be isolated or deferred. */
    public synchronized Decision observe(String regionId, Region region) {
        if (regionId == null || regionId.isBlank() || region == null) {
            throw new IllegalArgumentException("an observation needs a region");
        }
        State previous = states.get(regionId);
        long backlogBefore = previous == null ? region.backlog() : previous.backlogBefore();
        boolean overShare = region.selfMs() > region.shareMs();
        boolean backlogFalling = region.backlog() <= backlogBefore;
        boolean isolated = enabled && (overShare || (previous != null && previous.isolated()));
        long cleanTicks = 0L;
        boolean rolledBack = false;
        if (isolated) {
            if (overShare || !backlogFalling) {
                cleanTicks = 0L;
            } else {
                cleanTicks = (previous == null ? 0L : previous.cleanTicks()) + 1L;
                if (cleanTicks >= rollbackWindow) {
                    isolated = false;
                    cleanTicks = 0L;
                    rolledBack = true;
                    rollbacks++;
                }
            }
        }
        boolean writeDeferred = isolated || (enabled && overShare);
        if (enabled && isolated && overShare) {
            skipped++;
        }
        states.put(regionId, new State(region, isolated || writeDeferred, cleanTicks, region.backlog()));
        return new Decision(regionId, isolated, writeDeferred, rolledBack, cleanTicks, overShare,
            backlogFalling, barrierWaitCount());
    }

    public synchronized boolean isolated(String regionId) {
        State state = states.get(regionId);
        return state != null && state.isolated();
    }

    public synchronized long skipped() {
        return skipped;
    }

    public synchronized long rollbacks() {
        return rollbacks;
    }

    /** No server-wide barrier exists: the count is always zero. */
    public long barrierWaitCount() {
        return 0L;
    }

    public int rollbackWindowTicks() {
        return rollbackWindow;
    }

    public synchronized void reset() {
        states.clear();
        skipped = 0L;
        rollbacks = 0L;
    }
}
