/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteTapsTest {

    @Test
    void everyTapIsPassThroughUntilEnabled() {
        ChunkPipelineWriteTap chunk = new ChunkPipelineWriteTap();
        BlockEntityWriteTap be = new BlockEntityWriteTap();
        WorldLifecycleWriteTap lifecycle = new WorldLifecycleWriteTap();
        assertFalse(chunk.enabled());
        assertTrue(chunk.allows("w", 1L, 1L));
        assertTrue(be.allows("w", 2L, 1L));
        assertTrue(lifecycle.allows("w", 3L, 1L));
        chunk.enable((worldId, owner, version) -> false);
        assertFalse(chunk.allows("w", 1L, 1L));
        be.enable((worldId, owner, version) -> owner == 2L && version == 1L);
        assertTrue(be.allows("w", 2L, 1L));
        assertFalse(be.allows("w", 9L, 1L));
        lifecycle.enable((worldId, owner, version) -> version == 2L);
        assertFalse(lifecycle.allows("w", 3L, 1L));
        assertTrue(lifecycle.allows("w", 3L, 2L));
    }
}
