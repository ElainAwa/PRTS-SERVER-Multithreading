/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import java.util.LinkedHashMap;
import java.util.Map;

/** Internal production readout of the per-region observe events: isolation, deferred writes,
 * skips and the no-barrier fact, kept for the criteria view; no public key is emitted. */
final class RegionReadout {

    record State(String regionId, boolean isolated, boolean writeDeferred, boolean overShare,
                 long cleanTicks, long skipped, long rollbacks) {
    }

    private final SlowRegionIsolation isolation;
    private final Map<String, State> states = new LinkedHashMap<>();

    RegionReadout(SlowRegionIsolation isolation) {
        this.isolation = isolation;
    }

    synchronized State observe(String regionId, SlowRegionIsolation.Region region) {
        SlowRegionIsolation.Decision decision = isolation.observe(regionId, region);
        State state = new State(decision.regionId(), decision.isolated(), decision.writeDeferred(),
            decision.overShare(), decision.cleanTicks(), isolation.skipped(), isolation.rollbacks());
        states.put(regionId, state);
        return state;
    }

    synchronized Map<String, Object> criteriaView() {
        Map<String, Object> view = new LinkedHashMap<>();
        states.forEach((id, state) -> {
            view.put("region." + id + ".isolated", state.isolated());
            view.put("region." + id + ".write_deferred", state.writeDeferred());
            view.put("region." + id + ".skipped", state.skipped());
            view.put("region." + id + ".rollbacks", state.rollbacks());
        });
        view.put("region.barrier_waits", isolation.barrierWaitCount());
        return view;
    }

    synchronized void clear() {
        states.clear();
        isolation.reset();
    }

    synchronized int regions() {
        return states.size();
    }
}
