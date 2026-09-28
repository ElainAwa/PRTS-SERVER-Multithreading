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
 * Keeps {@code minecraft:block_entity_data} on an item meta that does not model it.
 *
 * <p>The block state meta declares that component, and the base meta collects the declared keys of
 * its subclasses into one handled-tag set. The base component-patch constructor then stores only
 * what that set does not contain, so a component handled by the subclass is skipped for every other
 * item meta as well: no field and no unhandled tag takes it, and the value is gone.</p>
 *
 * <p>The loss is observable for modded block items whose data lives in the block entity component,
 * a container item for example. A round trip through {@code CraftItemStack} - the copy a plugin or a
 * creative inventory slot performs - silently drops the component, so the item arrives without its
 * contents. The redirect below answers the handled-tag query with {@code false} for that one
 * component when the meta being built is not the block state meta, which routes the value into the
 * unhandled-tag map where it survives the round trip. Every other component keeps the set answer.</p>
 */
@Mixin(value = CraftMetaItem.class, remap = false)
public abstract class PrtsCraftMetaItemBlockEntityDataMixin {

    /**
     * Answers the handled-tag query of the component-patch constructor.
     *
     * @param handled the set the constructor asks
     * @param componentType the component being classified
     * @return {@code false} for the block entity component outside the block state meta, the answer
     *     of the set otherwise
     */
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
