/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

/** The extracted pre-scale prefix of {@code LivingEntity.tick}, callable through its program. */
public interface LivingEntityTickTailPreScaleStages {

    /** Runs the LivingEntity tick prefix before the scale branch; the branch stays in the host. */
    void prts$livingTickTailPreScale();
}
