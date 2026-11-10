/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.KernelDomain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-kernel instance registry of domains; not a static service locator. */
public final class DomainRegistry {

    private final Map<String, KernelDomain> domains = new LinkedHashMap<>();

    public synchronized boolean register(KernelDomain domain) {
        if (domain == null || domain.id() == null || domain.id().isEmpty()) {
            return false;
        }
        return domains.putIfAbsent(domain.id(), domain) == null;
    }

    public synchronized KernelDomain get(String id) {
        return domains.get(id);
    }

    public synchronized int size() {
        return domains.size();
    }
}
