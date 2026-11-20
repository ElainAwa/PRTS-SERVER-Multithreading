/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

/** The opt-in native program path: the host keeps the whole entity tick and the declared
 * navigation span is claimed, begun and published through the one controlled chain while a
 * shared-runtime worker runs only that span. It is a program slice, not a whole-tick owner. */
public final class OwnerNativeProgram {

    public static final String PROPERTY = "arclight.prts.ownerNativeProgram";

    private OwnerNativeProgram() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }
}
