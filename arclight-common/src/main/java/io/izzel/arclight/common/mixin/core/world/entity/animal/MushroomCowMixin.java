package io.izzel.arclight.common.mixin.core.world.entity.animal;

import net.minecraft.world.entity.animal.MushroomCow;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MushroomCow.class)
public abstract class MushroomCowMixin extends AnimalMixin {

    // Empty on purpose: extending the animal mixin is what carries those members into the mushroom
    // cow. The shear event used to be raised here, but a platform that moved the shearing of a
    // shearable entity out of the interaction into the shears item never reaches that call, so the
    // event is raised by the mixin scoped to that platform and by the vanilla scoped one instead.
}
