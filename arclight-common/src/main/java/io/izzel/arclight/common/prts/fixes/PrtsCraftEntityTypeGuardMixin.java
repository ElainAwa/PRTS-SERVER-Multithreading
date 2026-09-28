/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from PRTS-1.21.1, commit 5dfb40f29e0ab3b7191dea7da35a08191ed7a340
 * ("fix CraftEntity.getEntity type-table false hit causing ClassCastException").
 * Reworked as a standalone mixin of the prts.fixes category; see THIRD-PARTY.md.
 */
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
 * Falls back to the class based entity lookup when the type table converts the wrong entity.
 *
 * <p>The type table is keyed by entity type, and a custom entity that reuses a registered type
 * resolves to a converter whose cast cannot hold, which ends the call in a
 * {@code ClassCastException} instead of a Bukkit entity. Catching that conversion and resolving the
 * wrapper by entity class keeps every custom entity reachable and leaves the regular path
 * untouched.</p>
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
