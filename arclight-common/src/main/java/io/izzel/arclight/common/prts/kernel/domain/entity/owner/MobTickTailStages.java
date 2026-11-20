/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

/** The extracted tail of {@code Mob.tick}, callable by the transformed host through its program. */
public interface MobTickTailStages {

    /** Runs the Mob.tick tail that the host used to run inline. */
    void prts$mobTickTail();
}
