/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.papermc.paper.chat;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public interface ChatRenderer {

    Component render(Player source, Component sourceDisplayName, Component message, Audience viewer);
}
