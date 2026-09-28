package io.izzel.arclight.common.mixin.vanilla.world.entity.monster.piglin;

import io.izzel.arclight.common.bridge.core.world.entity.monster.piglin.PiglinBridge;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PiglinAi.class)
public abstract class PiglinAiMixin_Vanilla {

    // The shape where the barter check is a static helper of the piglin ai keeps the three override
    // sites here; a platform that asks the stack itself has no such call and overrides them in its
    // own scoped mixin.
    @Decorate(method = "stopHoldingOffHandItem", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/monster/piglin/PiglinAi;isBarterCurrency"
            + "(Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean arclight$customBarter(ItemStack stack, Piglin piglin) throws Throwable {
        return (boolean) DecorationOps.callsite().invoke(stack) || customBarterItem(stack, piglin);
    }

    @Decorate(method = "wantsToPickup", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/monster/piglin/PiglinAi;isBarterCurrency"
            + "(Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean arclight$customBanter2(ItemStack stack, Piglin piglin) throws Throwable {
        return (boolean) DecorationOps.callsite().invoke(stack) || customBarterItem(stack, piglin);
    }

    @Decorate(method = "canAdmire", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/monster/piglin/PiglinAi;isBarterCurrency"
            + "(Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean arclight$customBanter3(ItemStack stack, Piglin piglin) throws Throwable {
        return (boolean) DecorationOps.callsite().invoke(stack) || customBarterItem(stack, piglin);
    }

    private static boolean customBarterItem(ItemStack itemstack, Piglin piglin) {
        return ((PiglinBridge) piglin).bridge$getAllowedBarterItems().contains(itemstack.getItem());
    }
}
