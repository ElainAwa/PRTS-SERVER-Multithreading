/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk;

/** The eight completion layers of one chunk holder generation, in the order they are reached. A
 * loaded chunk is not an entity-ticking chunk, and neither is a sent or acknowledged one. */
public enum ChunkCompletionLayer {

    LOADED,
    TARGET_STATUS_READY,
    BLOCK_TICKING,
    ENTITY_TICKING,
    LIGHT_VALID,
    SEND_READY,
    QUEUED,
    ACK;

    /** Whether this layer is at or after the other layer. */
    public boolean atLeast(ChunkCompletionLayer other) {
        return ordinal() >= other.ordinal();
    }

    /** Whether this layer is before the other layer. */
    public boolean before(ChunkCompletionLayer other) {
        return ordinal() < other.ordinal();
    }
}
