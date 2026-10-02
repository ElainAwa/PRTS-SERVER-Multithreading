/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Dispatches the shear event after the entity answered that it can be sheared: a right click that
 * cannot shear raises nothing, and a cancelled event leaves the entity untouched because the shear
 * never runs. */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ShearsItem.class)
public abstract class PrtsShearsItemShearEventMixin {

    @Inject(method = "interactLivingEntity", cancellable = true,
        at = @At(value = "INVOKE", remap = false,
            target = "Lnet/neoforged/neoforge/common/IShearable;onSheared"
                + "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;"
                + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Ljava/util/List;"))
    private void prts$onShear(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand,
                              CallbackInfoReturnable<InteractionResult> cir) {
        if (!CraftEventFactory.handlePlayerShearEntityEvent(player, entity, stack, hand)) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }
}
