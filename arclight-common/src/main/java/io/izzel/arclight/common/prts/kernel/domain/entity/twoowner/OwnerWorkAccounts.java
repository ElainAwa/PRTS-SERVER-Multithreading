/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.twoowner;

import io.izzel.arclight.common.prts.kernel.SharedRuntime;
import io.izzel.arclight.common.prts.kernel.domain.entity.OwnerSite;
import io.izzel.arclight.common.prts.kernel.jobs.WorkAccount;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The work accounts of two owner sites over one shared runtime: every site owns its own
 * WorkAccount, so the offered, seated, pending and closed units of one owner stay separable, while
 * the one SharedRuntime keeps the single executor and its window account for the process. The
 * accounts are not derived from one another and one owner's close never moves another's closure.
 */
public final class OwnerWorkAccounts {

    private final SharedRuntime runtime;
    private final Map<String, WorkAccount> accounts = new LinkedHashMap<>();

    private OwnerWorkAccounts(SharedRuntime runtime, Collection<OwnerSite> sites) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        for (OwnerSite site : sites) {
            accounts.put(Objects.requireNonNull(site, "site").siteId(), new WorkAccount());
        }
    }

    /** Opens one account per owner site over the one shared runtime. */
    public static OwnerWorkAccounts open(SharedRuntime runtime, Collection<OwnerSite> sites) {
        return new OwnerWorkAccounts(runtime, sites);
    }

    /** The account of one owner site. */
    public WorkAccount accountOf(OwnerSite site) {
        Objects.requireNonNull(site, "site");
        WorkAccount account = accounts.get(site.siteId());
        if (account == null) {
            throw new IllegalArgumentException("the accounts do not name the site: " + site);
        }
        return account;
    }

    /** The per-site accounts, in the site order. */
    public Map<String, WorkAccount> accounts() {
        return Map.copyOf(accounts);
    }

    /** The one runtime window account every owner shares. */
    public WorkAccount sharedAccount() {
        return runtime.account();
    }

    public SharedRuntime runtime() {
        return runtime;
    }
}
