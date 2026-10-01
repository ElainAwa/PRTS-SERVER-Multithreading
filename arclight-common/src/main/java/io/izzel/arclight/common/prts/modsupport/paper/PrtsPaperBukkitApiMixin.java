/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit a349fffb97b9678b20b65a0eb1f290cc7912606f
 * ("add common Paper API compatibility").
 * Reworked for the prts.modsupport category; see THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.modsupport.paper;

import org.bukkit.Bukkit;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marks the Bukkit facade as a target of the mod interoperability category.
 *
 * <p>The category adds the static Paper version readout after the mixins of this target are
 * applied; a mixin has to exist for that target first, because Mixin only hands a class to the
 * configuration plugin once it carries a mixin. The class intentionally declares no member of its
 * own.</p>
 */
@Mixin(value = Bukkit.class, remap = false)
public abstract class PrtsPaperBukkitApiMixin {
}
