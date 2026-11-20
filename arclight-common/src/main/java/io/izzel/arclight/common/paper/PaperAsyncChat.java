/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.PluginManager;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Fires the paper async chat event from the legacy string event.
 * A cancelled legacy event is left alone. The legacy broadcast still sends the rendered string.
 */
public final class PaperAsyncChat {

    private PaperAsyncChat() {
    }

    public static void fire(PluginManager plugins, AsyncPlayerChatEvent legacy, boolean async) {
        if (legacy.isCancelled()) {
            return;
        }
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
        ChatRenderer renderer = paper.renderer() == null ? ChatRenderer.defaultRenderer() : paper.renderer();
        Component message = paper.message() == null ? Component.empty() : paper.message();
        Player speaker = legacy.getPlayer();
        Component rendered = renderer.render(speaker, displayName(speaker), message, new PlayerAudience(speaker));
        if (rendered == null || !rendered.equals(original)) {
            legacy.setMessage(PaperJoinMessages.write(rendered));
        }
    }

    private static Component component(String message) {
        Component read = PaperJoinMessages.read(message);
        return read == null ? Component.empty() : read;
    }

    private static Component displayName(Player player) {
        if (player == null) {
            return Component.empty();
        }
        return component(player.getDisplayName());
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

                    @Override
                    public void remove() {
                        source.remove();
                    }
                };
            }

            @Override
            public int size() {
                return players.size();
            }

            @Override
            public boolean contains(Object candidate) {
                return players.contains(playerOf(candidate));
            }

            @Override
            public boolean remove(Object candidate) {
                return players.remove(playerOf(candidate));
            }
        };
    }

    private static Object playerOf(Object candidate) {
        if (candidate instanceof PlayerAudience audience) {
            return audience.player;
        }
        return candidate;
    }

    private static final class PlayerAudience implements Audience {

        private final Player player;

        private PlayerAudience(Player player) {
            this.player = player;
        }

        @Override
        public void sendMessage(Identity source, Component message, MessageType type) {
            if (this.player == null) {
                return;
            }
            this.player.sendMessage(PaperJoinMessages.write(message));
        }
    }
}
