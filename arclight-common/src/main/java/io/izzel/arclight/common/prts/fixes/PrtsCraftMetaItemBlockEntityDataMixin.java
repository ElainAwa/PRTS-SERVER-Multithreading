/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import org.bukkit.craftbukkit.v.inventory.CraftMetaBlockState;
import org.bukkit.craftbukkit.v.inventory.CraftMetaItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/**
 * Keeps {@code minecraft:block_entity_data} on an item meta that does not model it. The base
 * meta collects the declared keys of its subclasses, so a component handled by the block state
 * meta is dropped for every other meta and a CraftItemStack round trip loses it. Answering the
 * handled-tag query with false outside the block state meta routes the value into the unhandled
 * map, where it survives.
 */
@Mixin(value = CraftMetaItem.class, remap = false)
public abstract class PrtsCraftMetaItemBlockEntityDataMixin {

    @Redirect(method = "<init>(Lnet/minecraft/core/component/DataComponentPatch;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/Set;contains(Ljava/lang/Object;)Z"))
    private boolean prts$keepBlockEntityData(Set<DataComponentType<?>> handled, Object componentType) {
        if (componentType == DataComponents.BLOCK_ENTITY_DATA
            && !(((Object) this) instanceof CraftMetaBlockState)) {
            return false;
        }
        return handled.contains(componentType);
    }
}
