/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit a349fffb97b9678b20b65a0eb1f290cc7912606f
 * ("add common Paper API compatibility").
 * Reworked as a standalone mixin of the prts.modsupport category; see THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.modsupport;

import org.bukkit.Server;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Declares the Paper version readout on the server interface.
 *
 * <p>The implementation lives on {@code CraftServer}; declaring the method here is what makes
 * plugins that compile against the Paper API resolve it instead of failing with
 * {@code NoSuchMethodError}.</p>
 */
@Mixin(value = Server.class, remap = false)
public interface PrtsPaperServerApiMixin {

    /**
     * Returns the Minecraft version this server runs.
     *
     * @return the version string, for example {@code 1.21.1}
     */
    String getMinecraftVersion();
}
