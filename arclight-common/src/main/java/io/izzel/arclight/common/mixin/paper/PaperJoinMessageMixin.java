/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mixin.paper;

import io.izzel.arclight.common.paper.PaperJoinMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.PlayerJoinEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = PlayerJoinEvent.class, remap = false)
public abstract class PaperJoinMessageMixin {
    // Adds joinMessage() beside the string accessors, because the broadcast path still stores a string.
    // Injected on PlayerJoinEvent, the type plugins call.
    // A null component writes an empty string. An empty string reads back as null.

    @Shadow
    public abstract String getJoinMessage();

    @Shadow
    public abstract void setJoinMessage(String message);

    public Component joinMessage() {
        return PaperJoinMessages.read(this.getJoinMessage());
    }

    public void joinMessage(Component message) {
        this.setJoinMessage(PaperJoinMessages.write(message));
    }
}
