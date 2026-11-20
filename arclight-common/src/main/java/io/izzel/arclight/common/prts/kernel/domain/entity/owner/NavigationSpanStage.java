/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.world.entity.ai.navigation.PathNavigation;

/** The extracted navigation span stage: one entry for the original {@code PathNavigation.tick()} block. */
public final class NavigationSpanStage {

    private NavigationSpanStage() {
    }

    /** Runs the block on the calling thread; no permission, no deferral and no owner worker. */
    public static void run(PathNavigation navigation) {
        navigation.tick();
    }
}