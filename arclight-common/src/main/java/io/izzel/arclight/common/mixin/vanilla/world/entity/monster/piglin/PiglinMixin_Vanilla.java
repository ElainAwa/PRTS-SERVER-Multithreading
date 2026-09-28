package io.izzel.arclight.common.mixin.vanilla.world.entity.monster.piglin;

import io.izzel.arclight.common.bridge.core.world.entity.monster.piglin.PiglinBridge;
import io.izzel.arclight.common.mixin.core.world.entity.PathfinderMobMixin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Piglin.class)
public abstract class PiglinMixin_Vanilla extends PathfinderMobMixin implements PiglinBridge {

    // The shape that tests the held stack against a fixed item keeps the off hand barter override
    // here; a platform that asks the stack itself has no such call and overrides it in its own
    // scoped mixin.
    @Redirect(method = "holdInOffHand", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/ItemStack;is(Lnet/minecraft/world/item/Item;)Z"))
    private boolean arclight$customBarter(ItemStack itemStack, Item item) {
        return itemStack.is(item) || bridge$getAllowedBarterItems().contains(itemStack.getItem());
    }
}
