/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit 706c84378a1218b822bb077158141d6612b509fd
 * ("normalize null groups for Bukkit").
 * Reworked as a standalone mixin of the prts.fixes category; see THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.fixes;

import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Replaces a missing recipe group with the empty group the Bukkit API declares.
 *
 * <p>A recipe without a group carries {@code null} internally, and the Bukkit view of that recipe
 * would then hand {@code null} to plugins and to the recipe serialization, which expect the empty
 * string. Normalizing at the setter keeps every recipe view consistent without touching the
 * recipe implementations that feed it.</p>
 */
@Mixin(value = {CraftingRecipe.class, CookingRecipe.class, StonecuttingRecipe.class}, remap = false)
public abstract class PrtsRecipeGroupMixin {

    @ModifyVariable(method = "setGroup", at = @At("HEAD"), argsOnly = true)
    private String prts$normalizeGroup(String group) {
        return group == null ? "" : group;
    }
}
