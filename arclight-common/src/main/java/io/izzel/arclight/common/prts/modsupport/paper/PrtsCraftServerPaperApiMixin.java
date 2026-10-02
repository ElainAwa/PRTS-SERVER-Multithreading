/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from the FeudalKings fork of Arclight, commit a349fffb97b9678b20b65a0eb1f290cc7912606f; see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.modsupport.paper;

import net.minecraft.server.dedicated.DedicatedServer;
import org.bukkit.craftbukkit.v.CraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Answers the Paper version readout from the running server, not from a compile-time constant.
 */
@Mixin(value = CraftServer.class, remap = false)
public abstract class PrtsCraftServerPaperApiMixin {

    @Shadow
    public abstract DedicatedServer getServer();

    public String getMinecraftVersion() {
        return this.getServer().getServerVersion();
    }
}
