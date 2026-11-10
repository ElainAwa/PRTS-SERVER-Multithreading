/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

/** Host-side AI capture seam; live ServerLevel implementation comes with the P2 host hook. */
public interface AiHostCapture {

    AiArenaPayload.Snapshot capture(String worldId, long tick, long worldEpoch, long generation);
}
