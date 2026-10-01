/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit a349fffb97b9678b20b65a0eb1f290cc7912606f
 * ("add common Paper API compatibility").
 * Reworked as a standalone mixin of the prts.modsupport category; see THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.modsupport.paper;

import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.InventoryView;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Declares the Paper inventory-opening methods on the human entity interface.
 *
 * <p>Each method opens the view of one work block with the vanilla menu provider of that block, so
 * plugins that compile against the Paper API reach the same behaviour here; the implementations are
 * contributed by {@link PrtsCraftHumanEntityPaperApiMixin}.</p>
 */
@Mixin(value = HumanEntity.class, remap = false)
public interface PrtsPaperHumanEntityApiMixin {

    /**
     * Opens an anvil view.
     *
     * @param location block to open, or {@code null} for the current position
     * @param force    whether the block type check is skipped
     * @return the opened view, or {@code null} when the view could not be opened
     */
    InventoryView openAnvil(Location location, boolean force);

    /**
     * Opens a cartography table view.
     *
     * @param location block to open, or {@code null} for the current position
     * @param force    whether the block type check is skipped
     * @return the opened view, or {@code null} when the view could not be opened
     */
    InventoryView openCartographyTable(Location location, boolean force);

    /**
     * Opens a grindstone view.
     *
     * @param location block to open, or {@code null} for the current position
     * @param force    whether the block type check is skipped
     * @return the opened view, or {@code null} when the view could not be opened
     */
    InventoryView openGrindstone(Location location, boolean force);

    /**
     * Opens a loom view.
     *
     * @param location block to open, or {@code null} for the current position
     * @param force    whether the block type check is skipped
     * @return the opened view, or {@code null} when the view could not be opened
     */
    InventoryView openLoom(Location location, boolean force);

    /**
     * Opens a smithing table view.
     *
     * @param location block to open, or {@code null} for the current position
     * @param force    whether the block type check is skipped
     * @return the opened view, or {@code null} when the view could not be opened
     */
    InventoryView openSmithingTable(Location location, boolean force);

    /**
     * Opens a stonecutter view.
     *
     * @param location block to open, or {@code null} for the current position
     * @param force    whether the block type check is skipped
     * @return the opened view, or {@code null} when the view could not be opened
     */
    InventoryView openStonecutter(Location location, boolean force);
}
