/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Converts a section-signed legacy string and an Adventure component.
 */
public final class PaperJoinMessages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private PaperJoinMessages() {
    }

    public static String write(Component message) {
        if (message == null) {
            return "";
        }
        return LEGACY.serialize(message);
    }

    public static Component read(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        return LEGACY.deserialize(message);
    }
}
