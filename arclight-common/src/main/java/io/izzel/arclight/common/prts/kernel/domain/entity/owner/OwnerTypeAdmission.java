/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.npc.Villager;

/** Which entity types the owner phase may take over. Villagers are the only type admitted today;
 * the predicate is the single place a later type slice extends, so selection and body admission
 * cannot drift apart. */
public final class OwnerTypeAdmission {

    private OwnerTypeAdmission() {
    }

    public static boolean admits(Entity entity) {
        // Exact type, not every Cow subclass: the mushroom variant carries state this slice has
        // not validated; a later slice names it explicitly or refuses it.
        return entity instanceof Villager
            || (entity != null && entity.getClass() == Cow.class);
    }
}
