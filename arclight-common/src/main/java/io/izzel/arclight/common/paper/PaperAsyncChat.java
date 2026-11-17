/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.PluginManager;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;

public final class PaperAsyncChat {

    private PaperAsyncChat() {
    }

    public static void fire(PluginManager plugins, AsyncPlayerChatEvent legacy, boolean async) {
        Component original = component(legacy.getMessage());
        AsyncChatEvent paper = new AsyncChatEvent(async, legacy.getPlayer(), viewers(legacy.getRecipients()),
            ChatRenderer.defaultRenderer(), original, original, null);
        plugins.callEvent(paper);
        apply(legacy, original, paper);
    }

    public static void apply(AsyncPlayerChatEvent legacy, Component original, AsyncChatEvent paper) {
        if (paper.isCancelled()) {
            legacy.setCancelled(true);
            return;
        }
        if (paper.message() == null || !paper.message().equals(original)) {
            legacy.setMessage(PaperJoinMessages.write(paper.message()));
        }
    }

    private static Component component(String message) {
        Component read = PaperJoinMessages.read(message);
        return read == null ? Component.empty() : read;
    }

    private static Set<Audience> viewers(Set<Player> players) {
        return new AbstractSet<>() {
            @Override
            public Iterator<Audience> iterator() {
                Iterator<Player> source = players.iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return source.hasNext();
                    }

                    @Override
                    public Audience next() {
                        return new PlayerAudience(source.next());
                    }
                };
            }

            @Override
            public int size() {
                return players.size();
            }
        };
    }

    private static final class PlayerAudience implements Audience {

        private PlayerAudience(Player player) {
        }
    }
}
