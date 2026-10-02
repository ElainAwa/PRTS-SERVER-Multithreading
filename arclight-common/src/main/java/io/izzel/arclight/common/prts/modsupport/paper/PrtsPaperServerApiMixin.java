/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from the FeudalKings fork of Arclight, commit a349fffb97b9678b20b65a0eb1f290cc7912606f; see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.modsupport.paper;

import org.bukkit.Server;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = Server.class, remap = false)
public interface PrtsPaperServerApiMixin {

    String getMinecraftVersion();
}
