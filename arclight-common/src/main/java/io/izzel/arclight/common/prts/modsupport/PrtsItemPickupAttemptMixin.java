/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit e195f79e06113774502c4986da0b3e63150f1455
 * ("add player attempt pickup event").
 * Reworked as a standalone mixin of the prts.modsupport category; see THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.modsupport;

import io.izzel.arclight.common.bridge.core.entity.EntityBridge;
import io.izzel.arclight.common.bridge.core.server.level.ServerPlayerBridge;
import io.izzel.arclight.common.bridge.core.world.entity.player.InventoryBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires the attempt-pickup event before an item stack is taken from the ground.
 *
 * <p>The event belongs to the plugin API surface this server advertises; plugins that listen to it
 * expect it for every pickup attempt, including the attempts the inventory would refuse. The
 * callback runs at the head of {@code ItemEntity#playerTouch} and therefore before the pickup
 * logic, which keeps the upstream pickup path untouched when no listener cancels.</p>
 */
@Mixin(ItemEntity.class)
public abstract class PrtsItemPickupAttemptMixin {

    @Shadow
    private int pickupDelay;

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void prts$fireAttemptPickup(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer) || this.pickupDelay > 0) {
            return;
        }
        ItemStack stack = ((ItemEntity) (Object) this).getItem();
        if (stack.isEmpty()) {
            return;
        }
        int count = stack.getCount();
        int canHold = ((InventoryBridge) serverPlayer.getInventory()).bridge$canHold(stack);
        PlayerAttemptPickupItemEvent event = new PlayerAttemptPickupItemEvent(
            ((ServerPlayerBridge) serverPlayer).bridge$getBukkitEntity(),
            (Item) ((EntityBridge) this).bridge$getBukkitEntity(),
            count - canHold);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            return;
        }
        if (event.getFlyAtPlayer()) {
            serverPlayer.take((ItemEntity) (Object) this, count);
        }
        ci.cancel();
    }
}
