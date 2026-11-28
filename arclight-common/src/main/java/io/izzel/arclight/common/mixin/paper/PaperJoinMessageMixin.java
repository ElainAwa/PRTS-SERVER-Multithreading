/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mixin.paper;

import io.izzel.arclight.common.paper.PaperJoinMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.PlayerJoinEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = PlayerJoinEvent.class, remap = false)
public abstract class PaperJoinMessageMixin {
    // Adds joinMessage() on PlayerJoinEvent. A null component writes empty; an empty string reads as null.

    @Shadow
    public abstract String getJoinMessage();

    @Shadow
    public abstract void setJoinMessage(String message);

    /** Returns the join message as an Adventure component. */
    public Component joinMessage() {
        return PaperJoinMessages.read(this.getJoinMessage());
    }

    /** Replaces the join message from an Adventure component. */
    public void joinMessage(Component message) {
        this.setJoinMessage(PaperJoinMessages.write(message));
    }
}
