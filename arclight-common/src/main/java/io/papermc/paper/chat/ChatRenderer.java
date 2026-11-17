/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.papermc.paper.chat;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public interface ChatRenderer {

    Component render(Player source, Component sourceDisplayName, Component message, Audience viewer);

    static ChatRenderer defaultRenderer() {
        return (source, sourceDisplayName, message, viewer) -> message;
    }

    static ChatRenderer viewerUnaware(ViewerUnaware renderer) {
        return (source, sourceDisplayName, message, viewer) -> renderer.render(source, sourceDisplayName, message);
    }

    interface ViewerUnaware {

        Component render(Player source, Component sourceDisplayName, Component message);
    }
}
