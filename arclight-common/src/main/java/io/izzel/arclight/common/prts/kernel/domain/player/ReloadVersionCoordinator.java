/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.player;

import java.util.Objects;

/** The reload coordinator: the registry, datapack, capability and plan epochs publish as one
 * immutable bundle at a safe point; a task that started under the old bundle either finishes on its
 * old snapshot or is refused as stale at the cut, and a result that crosses the cut never mixes the
 * two bundles. */
public final class ReloadVersionCoordinator {

    /** The four versions a reload publishes together. */
    public record Bundle(long registryEpoch, long datapackVersion, long capabilityEpoch,
                         long planEpoch) {

        public Bundle {
            if (registryEpoch < 0L || datapackVersion < 0L || capabilityEpoch < 0L
                || planEpoch < 0L) {
                throw new IllegalArgumentException("a reload bundle cannot name a negative epoch");
            }
        }
    }

    /** What one safe-point switch did. */
    public record SwitchResult(boolean switched, long bundleEpoch, String reason) {
    }

    private Bundle bundle;
    private long bundleEpoch;
    private int inFlight;
    private long refusedBusy;
    private long staleResults;

    public ReloadVersionCoordinator(Bundle initial) {
        this.bundle = Objects.requireNonNull(initial, "initial");
    }

    /** Starts one in-flight task pinned to the current bundle epoch. */
    public synchronized long beginFlight() {
        inFlight++;
        return bundleEpoch;
    }

    /** Publishes the new bundle only when no task is in flight; otherwise it is refused busy. */
    public synchronized SwitchResult switchAtSafePoint(Bundle next) {
        Objects.requireNonNull(next, "next");
        if (inFlight > 0) {
            refusedBusy++;
            return new SwitchResult(false, bundleEpoch, "busy-in-flight");
        }
        bundle = next;
        bundleEpoch++;
        return new SwitchResult(true, bundleEpoch, "switched");
    }

    /** A result of an older bundle epoch is refused, never mixed into the new rules. */
    public synchronized boolean completeFlight(long flightEpoch) {
        if (flightEpoch != bundleEpoch) {
            staleResults++;
            if (inFlight > 0) {
                inFlight--;
            }
            return false;
        }
        if (inFlight > 0) {
            inFlight--;
        }
        return true;
    }

    /** Whether one flight of that epoch may still read the current bundle. */
    public synchronized boolean current(long flightEpoch) {
        return flightEpoch == bundleEpoch;
    }

    public synchronized Bundle bundle() {
        return bundle;
    }

    public synchronized long bundleEpoch() {
        return bundleEpoch;
    }

    public synchronized int inFlight() {
        return inFlight;
    }

    public synchronized long refusedBusy() {
        return refusedBusy;
    }

    public synchronized long staleResults() {
        return staleResults;
    }
}
