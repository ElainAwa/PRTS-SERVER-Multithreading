package io.izzel.arclight.common.mixin.core.world.entity.animal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Sheep;
import org.bukkit.event.entity.SheepRegrowWoolEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.world.entity.animal.Sheep.class)
public abstract class SheepMixin extends AnimalMixin {

    //Force drop handler moved to PSI

    @Inject(method = "ate", cancellable = true, at = @At("HEAD"))
    private void arclight$regrow(CallbackInfo ci) {
        SheepRegrowWoolEvent event = new SheepRegrowWoolEvent((Sheep) this.getBukkitEntity());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    // The shear event is not raised here any more. A platform that moved the shearing of a shearable
    // entity out of the interaction into the shears item has no shear call left in mobInteract, so an
    // injector anchored on that call would silently match nothing there; the event is raised by the
    // mixin scoped to that platform and, for the shape that still shears inside the interaction, by
    // the vanilla scoped one.
}
