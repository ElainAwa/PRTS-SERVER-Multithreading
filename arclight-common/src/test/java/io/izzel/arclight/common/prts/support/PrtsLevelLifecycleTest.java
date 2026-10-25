/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrtsLevelLifecycleTest {

    @Test
    void aBlankIdIsRefusedAndTheSetStaysEmpty() {
        PrtsLevelLifecycle lifecycle = new PrtsLevelLifecycle();
        lifecycle.retain(lifecycle.load(null));
        assertEquals(PrtsLevelLifecycle.Mark.REFUSED, lifecycle.current().mark);
        assertEquals(PrtsLevelLifecycle.Refusal.MISSING_ID, lifecycle.current().refusal);
        lifecycle.retain(lifecycle.load(""));
        lifecycle.retain(lifecycle.unload(null));
        assertFalse(lifecycle.current().loadedIds.contains("minecraft:overworld"));
    }

    @Test
    void theFirstLoadIsKeptAndASecondLoadIsAReload() {
        PrtsLevelLifecycle lifecycle = new PrtsLevelLifecycle();
        lifecycle.retain(lifecycle.load("minecraft:overworld"));
        assertEquals(PrtsLevelLifecycle.Mark.LOADED, lifecycle.current().mark);
        assertEquals(PrtsLevelLifecycle.Refusal.NONE, lifecycle.current().refusal);
        assertTrue(lifecycle.current().loadedIds.contains("minecraft:overworld"));
        lifecycle.retain(lifecycle.load("minecraft:overworld"));
        assertEquals(PrtsLevelLifecycle.Mark.RELOADED, lifecycle.current().mark);
        assertTrue(lifecycle.current().loadedIds.contains("minecraft:overworld"));
    }

    @Test
    void unloadRemovesOnlyALoadedId() {
        PrtsLevelLifecycle lifecycle = new PrtsLevelLifecycle();
        lifecycle.retain(lifecycle.load("dim:a"));
        lifecycle.retain(lifecycle.unload("dim:a"));
        assertEquals(PrtsLevelLifecycle.Mark.UNLOADED, lifecycle.current().mark);
        assertFalse(lifecycle.current().loadedIds.contains("dim:a"));
        lifecycle.retain(lifecycle.unload("dim:a"));
        assertEquals(PrtsLevelLifecycle.Mark.REFUSED, lifecycle.current().mark);
        assertEquals(PrtsLevelLifecycle.Refusal.NOT_LOADED, lifecycle.current().refusal);
        lifecycle.retain(lifecycle.load("dim:b"));
        assertTrue(lifecycle.current().loadedIds.contains("dim:b"));
        assertFalse(lifecycle.current().loadedIds.contains("dim:a"));
    }

    @Test
    void anUnretainedLoadDoesNotBecomeVisible() {
        PrtsLevelLifecycle lifecycle = new PrtsLevelLifecycle();
        PrtsLevelLifecycle.Snapshot next = lifecycle.load("dim:held");
        assertEquals(PrtsLevelLifecycle.Mark.LOADED, next.mark);
        assertFalse(lifecycle.current().loadedIds.contains("dim:held"));
        lifecycle.retain(next);
        assertTrue(lifecycle.current().loadedIds.contains("dim:held"));
    }
}
