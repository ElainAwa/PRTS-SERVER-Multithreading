/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The totem death protection body, moved here so the method keeps the {@code removeEffectsCuredBy}
 * call third party injectors anchor on: a call reached through a helper, or written in after the
 * mixins were merged, is invisible to them. A change to the shared body has to be applied here too.
 */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.bridge.core.world.entity.LivingEntityBridge;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gameevent.GameEvent;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v.CraftEquipmentSlot;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(LivingEntity.class)
public abstract class PrtsLivingEntityTotemMixin {

    @Shadow(remap = false)
    public abstract boolean removeEffectsCuredBy(net.neoforged.neoforge.common.EffectCure cure);

    /** @author IzzelAliz @reason the cured-by-category call has to stay an instruction of this method */
    @Overwrite
    private boolean checkTotemDeathProtection(DamageSource damageSourceIn) {
        LivingEntity self = (LivingEntity) (Object) this;
        LivingEntityBridge bridge = (LivingEntityBridge) (Object) this;
        if (damageSourceIn.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        } else {
            ItemStack itemstack = null;

            ItemStack itemstack1 = ItemStack.EMPTY;
            org.bukkit.inventory.EquipmentSlot bukkitHand = null;
            for (InteractionHand hand : InteractionHand.values()) {
                itemstack1 = self.getItemInHand(hand);
                if (itemstack1.is(Items.TOTEM_OF_UNDYING)
                    && bridge.bridge$forge$onLivingUseTotem(self, damageSourceIn, itemstack1, hand)) {
                    itemstack = itemstack1.copy();
                    bukkitHand = CraftEquipmentSlot.getHand(hand);
                    break;
                }
            }

            EntityResurrectEvent event = new EntityResurrectEvent(bridge.bridge$getBukkitEntity(), bukkitHand);
            event.setCancelled(itemstack == null);
            Bukkit.getPluginManager().callEvent(event);

            if (!event.isCancelled()) {
                if (!itemstack1.isEmpty()) {
                    itemstack1.shrink(1);
                }
                if (itemstack != null && self instanceof ServerPlayer serverplayerentity) {
                    serverplayerentity.awardStat(Stats.ITEM_USED.get(Items.TOTEM_OF_UNDYING));
                    CriteriaTriggers.USED_TOTEM.trigger(serverplayerentity, itemstack);
                    self.gameEvent(GameEvent.ITEM_INTERACT_FINISH);
                }

                self.setHealth(1.0F);
                // Kept for third party injectors that anchor on this call from inside the method.
                this.removeEffectsCuredBy(net.neoforged.neoforge.common.EffectCures.PROTECTED_BY_TOTEM);
                bridge.bridge$pushEffectCause(EntityPotionEffectEvent.Cause.TOTEM);
                self.removeAllEffects();
                bridge.bridge$pushEffectCause(EntityPotionEffectEvent.Cause.TOTEM);
                self.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1), (Entity) null);
                bridge.bridge$pushEffectCause(EntityPotionEffectEvent.Cause.TOTEM);
                self.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1), (Entity) null);
                bridge.bridge$pushEffectCause(EntityPotionEffectEvent.Cause.TOTEM);
                self.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 1), (Entity) null);
                self.level().broadcastEntityEvent(self, (byte) 35);
            }
            return !event.isCancelled();
        }
    }
}
