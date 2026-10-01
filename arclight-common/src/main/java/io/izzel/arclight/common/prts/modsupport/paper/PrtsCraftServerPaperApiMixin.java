/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit a349fffb97b9678b20b65a0eb1f290cc7912606f
 * ("add common Paper API compatibility").
 * Reworked as a standalone mixin of the prts.modsupport category; see THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.modsupport.paper;

import net.minecraft.server.dedicated.DedicatedServer;
import org.bukkit.craftbukkit.v.CraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Implements the Paper version readout on the server implementation.
 *
 * <p>The value comes from the running server itself, so a plugin sees the version of the platform
 * it is actually attached to instead of a compile-time constant.</p>
 */
@Mixin(value = CraftServer.class, remap = false)
public abstract class PrtsCraftServerPaperApiMixin {

    @Shadow
    public abstract DedicatedServer getServer();

    /**
     * Returns the Minecraft version this server runs.
     *
     * @return the version string, for example {@code 1.21.1}
     */
    public String getMinecraftVersion() {
        return this.getServer().getServerVersion();
    }
}
