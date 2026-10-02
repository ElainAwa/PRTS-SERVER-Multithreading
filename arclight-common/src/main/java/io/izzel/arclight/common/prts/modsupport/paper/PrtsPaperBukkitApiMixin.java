/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from the FeudalKings fork of Arclight, commit a349fffb97b9678b20b65a0eb1f290cc7912606f; see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.modsupport.paper;

import org.bukkit.Bukkit;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marks the Bukkit facade as a mixin target, so the configuration plugin is handed the class and
 * can add the static Paper version readout in its post-apply step. Declares no member of its own.
 */
@Mixin(value = Bukkit.class, remap = false)
public abstract class PrtsPaperBukkitApiMixin {
}
