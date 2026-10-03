/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.wiring;

import io.izzel.arclight.common.prts.kernel.KernelModule;

/** Binds the domains of this build to the kernel module. Called from the platform entry before the
 * first tick; the kernel holds the handle, this class builds it. */
public final class KernelWiring {

    private KernelWiring() {
    }

    /** Installs the entity domain on the module and returns it; installing again replaces the
     * previous instance of the same domain. */
    public static EntityDomain install() {
        KernelModule module = KernelModule.instance();
        EntityDomain domain = new EntityDomain(module);
        module.installDomain(domain);
        return domain;
    }
}
