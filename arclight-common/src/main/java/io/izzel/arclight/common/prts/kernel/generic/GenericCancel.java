/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

/** Domain-neutral cooperative cancellation token for a generic job. A body marks the effect
 * possible before it performs one, so a cancellation that lands mid-body can be told apart from
 * a cancellation that landed before the body could produce anything. */
public interface GenericCancel {

    boolean cancelled();

    boolean cancel();

    /** Declares that the body may now produce its effect. The flag is monotone: late calls and
     * calls on a token that never marks keep the default. */
    default void markEffectPossible() {
    }

    /** Whether the body declared that its effect became possible. */
    default boolean effectPossible() {
        return false;
    }
}
