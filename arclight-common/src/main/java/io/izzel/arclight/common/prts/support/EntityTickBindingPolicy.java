/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

/** Kernel-driven subscription condition for the real entity-tick entry. */
public final class EntityTickBindingPolicy {

    private EntityTickBindingPolicy() {
    }

    /** True while the kernel is enabled. */
    public static boolean shouldBind(boolean kernelEnabled) {
        return enabled(kernelEnabled);
    }

    private static boolean enabled(boolean kernelEnabled) {
        return kernelEnabled;
    }

}
