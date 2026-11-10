/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForcedMaterializerRegistryTest {

    @Test
    void unregisteredCountsLimitedRegisteredRunsAndDefaultOffIsSilent() {
        ForcedMaterializerRegistry registry = new ForcedMaterializerRegistry();
        assertFalse(registry.overrun("wp-chunk"), "default off");
        assertEquals(1L, registry.overruns());
        assertEquals(0L, registry.limited());

        registry.setEnabled(true);
        assertFalse(registry.overrun("wp-chunk"), "no materializer");
        assertEquals(1L, registry.limited());
        assertTrue(registry.register("wp-chunk", site -> true));
        assertTrue(registry.overrun("wp-chunk"));
        assertEquals(1L, registry.executed());
        assertEquals(1, registry.registered());
    }
}
