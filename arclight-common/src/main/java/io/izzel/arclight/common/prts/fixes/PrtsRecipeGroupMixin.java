/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from the FeudalKings fork of Arclight, commit 706c84378a1218b822bb077158141d6612b509fd; see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.fixes;

import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Normalizes a missing recipe group to the empty group the Bukkit API declares, so plugin views and
 * recipe serialization never see {@code null}. Normalizing at the setter covers every view.
 */
@Mixin(value = {CraftingRecipe.class, CookingRecipe.class, StonecuttingRecipe.class}, remap = false)
public abstract class PrtsRecipeGroupMixin {

    @ModifyVariable(method = "setGroup", at = @At("HEAD"), argsOnly = true)
    private String prts$normalizeGroup(String group) {
        return group == null ? "" : group;
    }
}
