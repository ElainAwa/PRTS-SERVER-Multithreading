/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from PRTS-1.21.1, commit 5dfb40f29e0ab3b7191dea7da35a08191ed7a340; see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.common.mod.server.entity.EntityClassLookup;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.entity.CraftEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiFunction;

/**
 * Falls back to the class based entity lookup when the entity type table converts to a wrapper
 * whose cast cannot hold, which would end the call in a ClassCastException. The regular path is
 * left untouched.
 */
@Mixin(value = CraftEntity.class, remap = false)
public abstract class PrtsCraftEntityTypeGuardMixin {

    @Redirect(method = "getEntity",
        at = @At(value = "INVOKE",
            target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object prts$convertOrResolveByClass(BiFunction<Object, Object, Object> convert,
                                                       Object server, Object entity) {
        try {
            return convert.apply(server, entity);
        } catch (ClassCastException mismatch) {
            Entity nmsEntity = (Entity) entity;
            return EntityClassLookup.getEntityTypeData(nmsEntity).convertFunction()
                .apply((CraftServer) server, nmsEntity);
        }
    }
}
