/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PaperJoinMessagesTest {

    @Test
    void aColoredComponentBecomesASectionString() {
        assertEquals("§cA", PaperJoinMessages.write(Component.text("A", NamedTextColor.RED)));
    }

    @Test
    void aNullComponentClearsTheStoredString() {
        assertEquals("", PaperJoinMessages.write(null));
        assertNull(PaperJoinMessages.read(""));
        assertNull(PaperJoinMessages.read(null));
    }
}
