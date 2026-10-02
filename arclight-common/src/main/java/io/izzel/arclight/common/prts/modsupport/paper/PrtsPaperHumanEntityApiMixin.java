/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from the FeudalKings fork of Arclight, commit a349fffb97b9678b20b65a0eb1f290cc7912606f; see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.modsupport.paper;

import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.InventoryView;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Declares the Paper inventory-opening methods on the human entity interface. A null location
 * means the current position, {@code force} skips the block type check, and the view is null
 * when it could not be opened; the implementations come from the CraftHumanEntity mixin.
 */
@Mixin(value = HumanEntity.class, remap = false)
public interface PrtsPaperHumanEntityApiMixin {

    InventoryView openAnvil(Location location, boolean force);

    InventoryView openCartographyTable(Location location, boolean force);

    InventoryView openGrindstone(Location location, boolean force);

    InventoryView openLoom(Location location, boolean force);

    InventoryView openSmithingTable(Location location, boolean force);

    InventoryView openStonecutter(Location location, boolean force);
}
