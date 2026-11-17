/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.bukkit.event.player;

import org.bukkit.entity.Player;

// The async constructor on PlayerEvent is package-private. This subclass is the only way to mark
// a paper chat event asynchronous without leaving the Bukkit event package.
public abstract class PaperPlayerEvent extends PlayerEvent {

    protected PaperPlayerEvent(Player who, boolean async) {
        super(who, async);
    }
}
