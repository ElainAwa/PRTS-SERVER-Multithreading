/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from the FeudalKings fork of Arclight, commit a349fffb97b9678b20b65a0eb1f290cc7912606f; see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.modsupport.paper;

import io.izzel.arclight.common.bridge.core.world.inventory.AbstractContainerMenuBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.v.entity.CraftHumanEntity;
import org.bukkit.inventory.InventoryView;
import org.spongepowered.asm.mixin.Mixin;

import java.lang.reflect.Field;

/**
 * Implements the Paper inventory-opening methods for a player. Each opens the vanilla menu
 * provider of the addressed work block and returns the Bukkit view of the opened menu.
 */
@Mixin(value = CraftHumanEntity.class, remap = false)
public abstract class PrtsCraftHumanEntityPaperApiMixin {

    public InventoryView openAnvil(Location location, boolean force) {
        return this.prts$openInventory(location, force, Material.ANVIL);
    }

    public InventoryView openCartographyTable(Location location, boolean force) {
        return this.prts$openInventory(location, force, Material.CARTOGRAPHY_TABLE);
    }

    public InventoryView openGrindstone(Location location, boolean force) {
        return this.prts$openInventory(location, force, Material.GRINDSTONE);
    }

    public InventoryView openLoom(Location location, boolean force) {
        return this.prts$openInventory(location, force, Material.LOOM);
    }

    public InventoryView openSmithingTable(Location location, boolean force) {
        return this.prts$openInventory(location, force, Material.SMITHING_TABLE);
    }

    public InventoryView openStonecutter(Location location, boolean force) {
        return this.prts$openInventory(location, force, Material.STONECUTTER);
    }

    private InventoryView prts$openInventory(Location location, boolean force, Material material) {
        org.spigotmc.AsyncCatcher.catchOp("open" + material);
        CraftHumanEntity self = (CraftHumanEntity) (Object) this;
        if (!(self.getHandle() instanceof ServerPlayer handle)) {
            return null;
        }
        Location target = location == null ? self.getLocation() : location;
        if (!force && target.getBlock().getType() != material) {
            return null;
        }
        Block block = switch (material) {
            case ANVIL -> Blocks.ANVIL;
            case CARTOGRAPHY_TABLE -> Blocks.CARTOGRAPHY_TABLE;
            case GRINDSTONE -> Blocks.GRINDSTONE;
            case LOOM -> Blocks.LOOM;
            case SMITHING_TABLE -> Blocks.SMITHING_TABLE;
            case STONECUTTER -> Blocks.STONECUTTER;
            default -> throw new IllegalArgumentException("Unsupported inventory block: " + material);
        };
        BlockPos position = new BlockPos(target.getBlockX(), target.getBlockY(), target.getBlockZ());
        MenuProvider provider = ((PrtsBlockMenuProviderInvoker) block)
            .prts$getMenuProvider(handle.level().getBlockState(position), handle.level(), position);
        handle.openMenu(provider);
        prts$setCheckReachable(handle, !force);
        return ((AbstractContainerMenuBridge) handle.containerMenu).bridge$getBukkitView();
    }

    private static void prts$setCheckReachable(ServerPlayer handle, boolean checkReachable) {
        try {
            Field field = handle.containerMenu.getClass().getField("checkReachable");
            field.setBoolean(handle.containerMenu, checkReachable);
        } catch (ReflectiveOperationException ignored) {
            // Field contributed by the platform container-menu patch; when it is missing the menu
            // keeps its default reachability check.
        }
    }
}
