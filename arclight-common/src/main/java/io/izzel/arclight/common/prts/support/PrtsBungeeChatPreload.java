/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from the FeudalKings fork of Arclight (commit 19f6536); see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.common.mod.server.ArclightServer;

/** Loads the Bungee chat API classes into the server class loader once, so plugins that send a
 * component to a proxy resolve them; a missing class stops the preload instead of the server start. */
public final class PrtsBungeeChatPreload {

    private static final String[] CHAT_CLASSES = {
        "net.md_5.bungee.api.chat.BaseComponent",
        "net.md_5.bungee.api.chat.TextComponent",
        "net.md_5.bungee.api.chat.ClickEvent",
        "net.md_5.bungee.api.chat.HoverEvent",
        "net.md_5.bungee.chat.ComponentSerializer"
    };

    private PrtsBungeeChatPreload() {
    }

    public static void preload() {
        ClassLoader loader = PrtsBungeeChatPreload.class.getClassLoader();
        for (String className : CHAT_CLASSES) {
            try {
                Class.forName(className, true, loader);
            } catch (ClassNotFoundException missing) {
                ArclightServer.LOGGER.warn("Bungee chat class {} is not available; skipping the preload", className);
                return;
            }
        }
        ArclightServer.LOGGER.debug("Preloaded the Bungee chat API classes");
    }
}
