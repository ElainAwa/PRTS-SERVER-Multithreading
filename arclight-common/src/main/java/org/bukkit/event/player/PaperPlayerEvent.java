/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.bukkit.event.player;

import org.bukkit.entity.Player;

/**
 * Marks a paper event asynchronous. The async constructor on the parent is package-private.
 */
public abstract class PaperPlayerEvent extends PlayerEvent {

    protected PaperPlayerEvent(Player who, boolean async) {
        super(who, async);
    }
}
