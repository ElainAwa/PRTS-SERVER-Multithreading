package io.izzel.arclight.common.mixin.core.world.entity.monster;

import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;

/** Empty early hook: its preApply step is the one that writes the seven-argument burst into the
 * real creeper method body before later third-party mixins scan that body. */
@Mixin(Creeper.class)
public abstract class CreeperEarlyHook {
}
