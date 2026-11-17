/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperAsyncChatTest {

    @Test
    void anUnchangedMessageStaysAsItWas() {
        AsyncPlayerChatEvent legacy = legacy("hello");
        Component original = PaperJoinMessages.read("hello");
        PaperAsyncChat.apply(legacy, original, event(legacy, original));
        assertEquals("hello", legacy.getMessage());
        assertFalse(legacy.isCancelled());
    }

    @Test
    void aChangedComponentIsWrittenBackAsASectionString() {
        AsyncPlayerChatEvent legacy = legacy("hello");
        Component original = PaperJoinMessages.read("hello");
        AsyncChatEvent paper = event(legacy, original);
        paper.message(Component.text("A", NamedTextColor.RED));
        PaperAsyncChat.apply(legacy, original, paper);
        assertEquals("§cA", legacy.getMessage());
    }

    @Test
    void cancellationReachesTheStringEvent() {
        AsyncPlayerChatEvent legacy = legacy("hello");
        Component original = PaperJoinMessages.read("hello");
        AsyncChatEvent paper = event(legacy, original);
        paper.setCancelled(true);
        PaperAsyncChat.apply(legacy, original, paper);
        assertTrue(legacy.isCancelled());
        assertEquals("hello", legacy.getMessage());
    }

    private static AsyncPlayerChatEvent legacy(String message) {
        return new AsyncPlayerChatEvent(false, player(), message, new HashSet<>());
    }

    private static AsyncChatEvent event(AsyncPlayerChatEvent legacy, Component original) {
        return new AsyncChatEvent(false, legacy.getPlayer(), Set.of(),
            (source, displayName, message, viewer) -> message, original, original, null);
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> {
                if ("getUniqueId".equals(method.getName())) {
                    return new UUID(0L, 1L);
                }
                if ("getName".equals(method.getName()) || "getDisplayName".equals(method.getName())) {
                    return "Ada";
                }
                Class<?> type = method.getReturnType();
                if (type == boolean.class) {
                    return false;
                }
                if (type == int.class) {
                    return 0;
                }
                if (type == long.class) {
                    return 0L;
                }
                return null;
            });
    }
}
