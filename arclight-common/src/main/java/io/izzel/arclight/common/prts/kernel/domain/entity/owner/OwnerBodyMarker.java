/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

/** Marks the thread that is running one opt-in parallel owner body. Only the shared-state guards
 * read it; the default host path never sets it, so every guard stays on its historical branch. */
public final class OwnerBodyMarker {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private OwnerBodyMarker() {
    }

    public static void enter() {
        ACTIVE.set(Boolean.TRUE);
    }

    public static void exit() {
        ACTIVE.remove();
    }

    public static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }
}
