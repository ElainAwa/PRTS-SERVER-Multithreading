/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import java.util.Set;

/** Enforcement context for pure compute jobs: declared read/write sets, owner/share facts, epoch.
 * No live world, commit, readings or self check is reachable from here. */
public final class JobExecutionContext {

    private final Set<String> declaredRead;
    private final Set<String> declaredWrite;
    private final Set<String> declaredOwner;
    private final long worldEpoch;
    private final double shareMs;
    private double chargedMs;
    private boolean exhausted;

    public JobExecutionContext(Set<String> declaredRead, Set<String> declaredWrite,
                               Set<String> declaredOwner, long worldEpoch, double shareMs) {
        this.declaredRead = Set.copyOf(declaredRead);
        this.declaredWrite = Set.copyOf(declaredWrite);
        this.declaredOwner = Set.copyOf(declaredOwner);
        this.worldEpoch = worldEpoch;
        this.shareMs = Math.max(0.0, shareMs);
    }

    public boolean allowsRead(String domain) { return declaredRead.contains(domain); }
    public boolean allowsWrite(String domain) { return declaredWrite.contains(domain); }
    public boolean allowsOwner(String owner) { return declaredOwner.contains(owner); }
    public long worldEpoch() { return worldEpoch; }
    public double shareMs() { return shareMs; }
    public double chargedMs() { return chargedMs; }

    public boolean exhausted() { return exhausted; }

    /** Charges pure-compute time against the declared share; returns false when over budget. */
    public synchronized boolean charge(double ms) {
        double next = chargedMs + Math.max(0.0, ms);
        if (next > shareMs) {
            exhausted = true;
            return false;
        }
        chargedMs = next;
        return true;
    }
}
