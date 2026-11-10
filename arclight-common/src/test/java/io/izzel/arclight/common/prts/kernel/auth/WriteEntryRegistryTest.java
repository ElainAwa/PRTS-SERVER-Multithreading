/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteEntryRegistryTest {

    @Test
    void registrySeparatesWiredFromUnwiredAndKeepsDefaultsOff() {
        assertEquals(6, WriteEntryRegistry.entries().size());
        assertTrue(WriteEntryRegistry.entries().stream().noneMatch(WriteEntryRegistry.Entry::defaultEnabled));
        assertEquals(0, WriteEntryRegistry.unwired().size());
        
        assertFalse(WriteEntryRegistry.entries().isEmpty());
    }
}
