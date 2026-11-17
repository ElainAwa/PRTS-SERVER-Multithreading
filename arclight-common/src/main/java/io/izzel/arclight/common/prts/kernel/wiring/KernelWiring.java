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
        return install(KernelModule.instance().ownerContext().primarySite());
    }

    /** Installs the entity domain as one owner site of the shared owner context. */
    public static EntityDomain install(
        io.izzel.arclight.common.prts.kernel.domain.entity.OwnerSite ownerSite) {
        KernelModule module = KernelModule.instance();
        EntityDomain domain = new EntityDomain(module, ownerSite);
        module.installDomain(domain);
        return domain;
    }

    /** Builds (without installing) one domain of the installed module as the given owner site. */
    public static EntityDomain create(
        io.izzel.arclight.common.prts.kernel.domain.entity.OwnerSite ownerSite) {
        return new EntityDomain(KernelModule.instance(), ownerSite);
    }

    /**
     * Installs the entity domain of every owner site of the process: with the two-owner property
     * off this is exactly the one-domain install; with it on, both sites build their own domain on
     * the same module and the same owner context, and both are installed.
     */
    public static java.util.List<EntityDomain> installTwoOwners() {
        KernelModule module = KernelModule.instance();
        java.util.List<io.izzel.arclight.common.prts.kernel.domain.entity.OwnerSite> sites =
            io.izzel.arclight.common.prts.kernel.domain.entity.twoowner.TwoOwnerSetup
                .sites(module.ownerContext());
        if (sites.size() == 1) {
            return java.util.List.of(install(sites.get(0)));
        }
        java.util.List<EntityDomain> domains = new java.util.ArrayList<>(sites.size());
        for (io.izzel.arclight.common.prts.kernel.domain.entity.OwnerSite site : sites) {
            EntityDomain domain = new EntityDomain(module, site);
            module.installDomain(domain);
            domains.add(domain);
        }
        return java.util.List.copyOf(domains);
    }
}
