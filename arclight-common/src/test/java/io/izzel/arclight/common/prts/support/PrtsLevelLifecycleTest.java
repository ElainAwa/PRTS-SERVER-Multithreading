/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrtsLevelLifecycleTest {

    @AfterEach
    void reset() {
        PrtsLevelLifecycle.resetForTests();
    }

    @Test
    void aBlankIdIsRefusedAndTheSetStaysEmpty() {
        assertEquals(PrtsLevelLifecycle.Mark.REFUSED, PrtsLevelLifecycle.loaded(null));
        assertEquals(PrtsLevelLifecycle.Refusal.MISSING_ID, PrtsLevelLifecycle.lastRefusal());
        assertEquals(PrtsLevelLifecycle.Mark.REFUSED, PrtsLevelLifecycle.loaded(""));
        assertEquals(PrtsLevelLifecycle.Mark.REFUSED, PrtsLevelLifecycle.unloaded(null));
        assertFalse(PrtsLevelLifecycle.isLoaded("minecraft:overworld"));
    }

    @Test
    void theFirstLoadIsKeptAndASecondLoadIsAReload() {
        assertEquals(PrtsLevelLifecycle.Mark.LOADED, PrtsLevelLifecycle.loaded("minecraft:overworld"));
        assertEquals(PrtsLevelLifecycle.Refusal.NONE, PrtsLevelLifecycle.lastRefusal());
        assertTrue(PrtsLevelLifecycle.isLoaded("minecraft:overworld"));
        assertEquals(PrtsLevelLifecycle.Mark.RELOADED, PrtsLevelLifecycle.loaded("minecraft:overworld"));
        assertTrue(PrtsLevelLifecycle.isLoaded("minecraft:overworld"));
        assertEquals(PrtsLevelLifecycle.Refusal.NONE, PrtsLevelLifecycle.lastRefusal());
    }

    @Test
    void unloadRemovesOnlyALoadedId() {
        PrtsLevelLifecycle.loaded("dim:a");
        assertEquals(PrtsLevelLifecycle.Mark.UNLOADED, PrtsLevelLifecycle.unloaded("dim:a"));
        assertFalse(PrtsLevelLifecycle.isLoaded("dim:a"));
        assertEquals(PrtsLevelLifecycle.Mark.REFUSED, PrtsLevelLifecycle.unloaded("dim:a"));
        assertEquals(PrtsLevelLifecycle.Refusal.NOT_LOADED, PrtsLevelLifecycle.lastRefusal());
        assertFalse(PrtsLevelLifecycle.isLoaded("dim:b"));
        PrtsLevelLifecycle.loaded("dim:b");
        assertTrue(PrtsLevelLifecycle.isLoaded("dim:b"));
    }
}
