/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit 36adbc7b4a58afe79c6e881dc4afbfbcaa7d2c94
 * ("fix(compat): support LuckPerms permissible injection").
 * See THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.support;

import org.bukkit.craftbukkit.v.entity.CraftHumanEntity;
import org.bukkit.permissions.PermissibleBase;

import java.lang.reflect.Field;

/**
 * Field handles for plugin code that injects a custom permissible implementation.
 *
 * <p>Plugins that replace the permissible of an online player reach for the two fields below
 * directly. They are not part of the Bukkit API and the lookup rules change from release to
 * release, so the fields are resolved once, here, and handed to the plugin by the class rewriter
 * instead of being looked up by the plugin itself.</p>
 */
public final class PrtsLuckPermsCompat {

    private static final Field HUMAN_ENTITY_PERMISSIBLE_FIELD = findField(CraftHumanEntity.class, "perm");
    private static final Field PERMISSIBLE_BASE_ATTACHMENTS_FIELD = findField(PermissibleBase.class, "attachments");

    private PrtsLuckPermsCompat() {
    }

    /**
     * @return the field that holds the permissible of a human entity
     */
    public static Field humanEntityPermissibleField() {
        return HUMAN_ENTITY_PERMISSIBLE_FIELD;
    }

    /**
     * @return the field that holds the attachments of a permissible
     */
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
