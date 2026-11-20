/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitProgress;

/** Refuses demand-bearing work until the chunk row has a bound progress producer. */
final class DemandSignalGate {

    private DemandSignalGate() {
    }

    /** Whether the existing chunk row's progress signal is bound; no new key or refusal code. */
    static boolean chunkSignalBound(WaitPointRegistry waitPoints) {
        if (waitPoints == null) {
            return false;
        }
        WaitProgress.Reading reading = waitPoints.progress().read("chunk");
        return reading != null && reading.bound();
    }
}
