/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from the FeudalKings fork of Arclight (commit 36adbc7); see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.support;

import org.bukkit.craftbukkit.v.entity.CraftHumanEntity;
import org.bukkit.permissions.PermissibleBase;

import java.lang.reflect.Field;

/** The two non-API field handles the class rewriter hands to plugins; the lookup rules change between releases. */
public final class PrtsLuckPermsCompat {

    private static final Field HUMAN_ENTITY_PERMISSIBLE_FIELD = findField(CraftHumanEntity.class, "perm");
    private static final Field PERMISSIBLE_BASE_ATTACHMENTS_FIELD = findField(PermissibleBase.class, "attachments");

    private PrtsLuckPermsCompat() {
    }

    public static Field humanEntityPermissibleField() {
        return HUMAN_ENTITY_PERMISSIBLE_FIELD;
    }

    public static Field permissibleBaseAttachmentsField() {
        return PERMISSIBLE_BASE_ATTACHMENTS_FIELD;
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
