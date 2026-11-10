/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteTapContextTest {

    @Test
    void enforceNaAndDisabledArePassThrough() {
        WriteTapContext noOwner = new WriteTapContext("w", null, 0L, 1L, "chunk", "chunk.submit");
        assertTrue(noOwner.enforceNa());
        ChunkPipelineWriteTap tap = new ChunkPipelineWriteTap();
        assertTrue(tap.allows(noOwner, context -> false));
    }
}
