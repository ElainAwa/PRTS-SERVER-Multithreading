/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk;

/** The eight completion layers of one holder generation, in the order they are reached. */
public enum ChunkCompletionLayer {

    LOADED,
    TARGET_STATUS_READY,
    BLOCK_TICKING,
    ENTITY_TICKING,
    LIGHT_VALID,
    SEND_READY,
    QUEUED,
    ACK;

    boolean atLeast(ChunkCompletionLayer other) {
        return ordinal() >= other.ordinal();
    }
}
