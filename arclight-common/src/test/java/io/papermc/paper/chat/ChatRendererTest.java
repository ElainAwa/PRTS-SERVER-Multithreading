/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.papermc.paper.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class ChatRendererTest {

    @Test
    void theDefaultRendererReturnsTheOriginalMessage() {
        Component message = Component.text("A", NamedTextColor.RED);
        Component rendered = ChatRenderer.defaultRenderer().render(null, Component.text("Ada"), message, null);
        assertSame(message, rendered);
    }

    @Test
    void aViewerUnawareRendererIgnoresTheViewer() {
        Component message = Component.text("hello");
        Component marked = Component.text("marked");
        ChatRenderer.ViewerUnaware unaware = (source, displayName, text) -> marked;
        Component rendered = ChatRenderer.viewerUnaware(unaware).render(null, Component.empty(), message, null);
        assertSame(marked, rendered);
    }
}
