/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.papermc.paper.event.player;

import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

import java.util.Set;

/**
 * The asynchronous paper chat event.
 */
public final class AsyncChatEvent extends AbstractChatEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public AsyncChatEvent(boolean async, Player player, Set<Audience> viewers, ChatRenderer renderer,
                           Component message, Component originalMessage, SignedMessage signedMessage) {
        super(async, player, viewers, renderer, message, originalMessage, signedMessage);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
