package io.izzel.arclight.common.mixin.core.world.entity.monster;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Suppresses the vanilla six-argument burst only after every default-priority third-party mixin has
 * seen that call site; the real burst is the seven-argument call injected before those mixins run. */
@Mixin(Creeper.class)
public abstract class CreeperSixArgNoOpMixin {

    @Redirect(method = "explodeCreeper",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;explode(Lnet/minecraft/world/entity/Entity;"
                + "DDDFLnet/minecraft/world/level/Level$ExplosionInteraction;)"
                + "Lnet/minecraft/world/level/Explosion;"))
    private Explosion arclight$noOpSixArgumentExplode(Level instance, Entity source, double x, double y,
                                                      double z, float radius,
                                                      Level.ExplosionInteraction interaction) {
        return null;
    }
}
