/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.nativeext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/** The hard cap of the pending obligations one substep may create: an obligation is admitted only
 * below the declared maximum, over-cap admission is refused and moves the substep to a safe
 * quarantine before anything is enqueued, and a normal or exceptional return reconciles the in-flight
 * count instead of letting it grow without bound. An absent payload answers N/A, never zero. */
public final class NativeSubstepObligationCap {

    /** What one admission did. */
    public record Admission(boolean admitted, long pending, String reason) {
    }

    private final NativeEngineOwner.Presence presence;
    private final int maxPending;
    private final Map<Long, Integer> pendingBySubstep = new LinkedHashMap<>();
    private long refused;
    private long reconciled;
    private boolean quarantined;

    public NativeSubstepObligationCap(NativeEngineOwner.Presence presence, int maxPending) {
        if (maxPending < 0) {
            throw new IllegalArgumentException("a substep obligation cap cannot be negative");
        }
        this.presence = presence;
        this.maxPending = maxPending;
    }

    /** Admits one obligation before its enqueue; the cap is checked before any effect. */
    public synchronized Admission admit(long substep) {
        if (presence != NativeEngineOwner.Presence.LOADED) {
            return new Admission(false, 0L, "NOT_LOADED_NA");
        }
        int pending = pendingBySubstep.getOrDefault(substep, 0);
        if (pending >= maxPending) {
            refused++;
            quarantined = true;
            return new Admission(false, pending, "over-cap");
        }
        pendingBySubstep.put(substep, pending + 1);
        return new Admission(true, pending + 1L, "admitted");
    }

    /** Completes one obligation exactly once; an absent obligation is a count of nothing. */
    public synchronized boolean complete(long substep) {
        int pending = pendingBySubstep.getOrDefault(substep, 0);
        if (pending <= 0) {
            return false;
        }
        pendingBySubstep.put(substep, pending - 1);
        return true;
    }

    /** Reconciles one substep after its normal or exceptional return; the count is cleared. */
    public synchronized int reconcile(long substep) {
        Integer pending = pendingBySubstep.remove(substep);
        int cleared = pending == null ? 0 : pending;
        reconciled += cleared;
        return cleared;
    }

    /** The pending obligations of one substep, or N/A while the payload is not loaded. */
    public synchronized OptionalLong pendingNa(long substep) {
        return presence == NativeEngineOwner.Presence.LOADED
            ? OptionalLong.of(pendingBySubstep.getOrDefault(substep, 0))
            : OptionalLong.empty();
    }

    public synchronized long refused() {
        return refused;
    }

    public synchronized long reconciled() {
        return reconciled;
    }

    public synchronized boolean quarantined() {
        return quarantined;
    }
}
