/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The shear event for the platform that moves the shearing of every shearable entity out of the
 * entity interaction and into the shears item: the entity is asked whether it can be sheared and,
 * only when the answer is yes, the shear runs. The event is dispatched at that second step, so a
 * right click that cannot shear anything does not raise it, and a cancelled event leaves the entity
 * untouched because the shear itself never runs.
 */
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
