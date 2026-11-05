/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mixin.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.event.player.PlayerJoinEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// Paper's join message is a component. The existing string accessors stay the storage.
@Mixin(value = PlayerJoinEvent.class, remap = false)
public abstract class PaperJoinMessageMixin {

    @Shadow
    public abstract String getJoinMessage();

    @Shadow
    public abstract void setJoinMessage(String message);

    public Component joinMessage() {
        String text = this.getJoinMessage();
        if (text == null) {
            return null;
        }
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    public void joinMessage(Component component) {
        this.setJoinMessage(component == null
            ? null
            : LegacyComponentSerializer.legacySection().serialize(component));
    }
}
