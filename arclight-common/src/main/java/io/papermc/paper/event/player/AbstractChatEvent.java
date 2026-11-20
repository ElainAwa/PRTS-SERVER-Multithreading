/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.papermc.paper.event.player;

import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.player.PaperPlayerEvent;

import java.util.Set;

/**
 * Shared state of a paper chat event: viewers, renderer and the message components.
 */
public abstract class AbstractChatEvent extends PaperPlayerEvent implements Cancellable {

    private final Set<Audience> viewers;
    private final Component originalMessage;
    private final SignedMessage signedMessage;
    private ChatRenderer renderer;
    private Component message;
    private boolean cancelled;

    protected AbstractChatEvent(boolean async, Player player, Set<Audience> viewers, ChatRenderer renderer,
                                 Component message, Component originalMessage, SignedMessage signedMessage) {
        super(player, async);
        this.viewers = viewers;
        this.renderer = renderer;
        this.message = message;
        this.originalMessage = originalMessage;
        this.signedMessage = signedMessage;
    }

    public final Set<Audience> viewers() {
        return this.viewers;
    }

    public final void renderer(ChatRenderer renderer) {
        this.renderer = renderer;
    }

    public final ChatRenderer renderer() {
        return this.renderer;
    }

    public final Component message() {
        return this.message;
    }

    public final void message(Component message) {
        this.message = message;
    }

    public final Component originalMessage() {
        return this.originalMessage;
    }

    public final SignedMessage signedMessage() {
        return this.signedMessage;
    }

    @Override
    public final boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public final void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
