/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import net.minecraft.server.level.ServerLevel;
import org.bukkit.World;

/**
 * The one key a world is named by: the snapshot stamps views with it, the write-back freezes it and
 * the write-right guard tracks the generation under it.
 */
public final class WorldKey {

    private WorldKey() {
    }

    public static String id(World world) {
        return world.getKey().toString();
    }

    public static String id(ServerLevel level) {
        return level.dimension().location().toString();
    }
}
