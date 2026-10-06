/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Turns the write right a frozen job declares into the one credential a writer may hold. There is no
 * entry that hands a right out by itself: the only call takes the graph the planning period froze, and
 * a token is minted only for a demand a declaration carried in that graph. The point keeps the right
 * while the declaring job is in the newest plan and releases it when the job is gone; the registry
 * sweep reclaims it when the hold window runs out. Off, it grants nothing, releases nothing and counts
 * nothing, so the registry sees exactly what it saw before. */
public final class OwnerGrantPoint {

    private final OwnerRegistry owners;
    private final WriteVersionSlots versions;
    private final Set<OwnershipDomain> held = new HashSet<>();

    private final AtomicLong nextEpoch = new AtomicLong(1L);
    private final AtomicLong declared = new AtomicLong();
    private final AtomicLong granted = new AtomicLong();
    private final AtomicLong releasedByPlan = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong refusedCrossWorld = new AtomicLong();
    private final AtomicLong refusedExpired = new AtomicLong();
    private final AtomicLong refusedNoVersion = new AtomicLong();
    private final AtomicLong refusedDoubleHolder = new AtomicLong();
    private final AtomicLong refusedUndeclared = new AtomicLong();
    private volatile boolean enabled;
    private volatile RejectTrigger lastTrigger;
    private volatile RejectTrigger.Diag5 lastRefusal;
    private volatile String lastRefusedDomain = "";

    public OwnerGrantPoint(OwnerRegistry owners, WriteVersionSlots versions) {
        this.owners = owners;
        this.versions = versions;
    }

    /** Decides whether declared write rights are granted at all. */
    public void refresh(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Grants the rights the frozen graph declares and releases the ones its declarations dropped. The
     * graph is frozen once per tick and handed over here once, so the whole face is a function of the
     * declarations of that tick and of the tokens the ticks before it left behind. */
    public void apply(JobGraph graph, long tickIndex) {
        if (!enabled) {
            return;
        }
        Set<OwnershipDomain> declaredNow = new HashSet<>();
        List<JobGraph.Node> nodes = graph == null ? List.of() : graph.nodes();
        for (JobGraph.Node node : nodes) {
            for (JobDeclaration.OwnerDemand demand : node.ownerDemands()) {
                declared.incrementAndGet();
                grant(node, demand, tickIndex, declaredNow);
            }
        }
        releaseUndeclared(declaredNow);
    }

    private void grant(JobGraph.Node node, JobDeclaration.OwnerDemand demand, long tickIndex,
                       Set<OwnershipDomain> declaredNow) {
        if (!demand.worldId().equals(node.worldId())) {
            refusedCrossWorld.incrementAndGet();
            refuse(RejectTrigger.CROSS_WORLD_DIRECT_WRITE, demand, tickIndex);
            return;
        }
        if (!declared(node, demand)) {
            refusedUndeclared.incrementAndGet();
            refuse(RejectTrigger.DOMAIN_SET_NOT_SPLIT, demand, tickIndex);
            return;
        }
        if (demand.holdTicks() <= 0) {
            refusedExpired.incrementAndGet();
            refuse(RejectTrigger.TOKEN_EXPIRED, demand, tickIndex);
            return;
        }
        long version = versions.carried(demand.worldId(), demand.level(), demand.domainId());
        if (version == WriteVersionSlots.NOT_CARRIED) {
            refusedNoVersion.incrementAndGet();
            refuse(RejectTrigger.VERSION_SLOT_MISMATCH, demand, tickIndex);
            return;
        }
        OwnershipDomain domain = new OwnershipDomain(demand.worldId(), demand.level(),
            demand.domainId());
        OwnerToken installed = owners.lookup(demand.worldId(), demand.level(), demand.domainId())
            .orElse(null);
        if (installed != null && installed.expiredAt(tickIndex)) {
            owners.release(domain, installed.holderSiteId());
            installed = null;
        }
        // The holder that already holds the domain keeps its generation, so the registry reads the
        // ask of the same holder as the same holder asking again and the ask of any other one as the
        // second holder it is. The registry is the only place either is decided.
        long epoch = installed == null ? nextEpoch.getAndIncrement() : installed.epoch();
        OwnerToken candidate = new OwnerToken(demand.worldId(), demand.level(), demand.domainId(),
            epoch, version, tickIndex, tickIndex + demand.holdTicks(), demand.holderKind(),
            demand.holderSiteId());
        boolean first = installed == null;
        if (!owners.acquire(candidate)) {
            refusedDoubleHolder.incrementAndGet();
            refuse(RejectTrigger.OWNER_MISMATCH, demand, tickIndex);
            return;
        }
        held.add(domain);
        declaredNow.add(domain);
        if (first) {
            granted.incrementAndGet();
        }
    }

    private void releaseUndeclared(Set<OwnershipDomain> declaredNow) {
        Iterator<OwnershipDomain> iterator = held.iterator();
        while (iterator.hasNext()) {
            OwnershipDomain domain = iterator.next();
            if (declaredNow.contains(domain)) {
                continue;
            }
            OwnerToken token = owners.lookup(domain.worldId(), domain.level(), domain.domainId())
                .orElse(null);
            if (token != null && owners.release(domain, token.holderSiteId())) {
                releasedByPlan.incrementAndGet();
            }
            iterator.remove();
        }
    }

    /** Whether the declaring job declares the very right the demand asks for: the world and the domain
     * have to be covered by the write set the job froze, so a demand cannot reach past its own
     * declaration. The level is not compared with the level of the declaration: a right is held at the
     * level the plan keeps the version of a declared domain at, which is what the token has to name. */
    private static boolean declared(JobGraph.Node node, JobDeclaration.OwnerDemand demand) {
        for (JobDeclaration.DomainRef ref : node.writeSet()) {
            if (ref.worldId().equals(demand.worldId()) && ref.domainId().equals(demand.domainId())) {
                return true;
            }
        }
        return false;
    }

    private void refuse(RejectTrigger trigger, JobDeclaration.OwnerDemand demand, long tickIndex) {
        refused.incrementAndGet();
        lastTrigger = trigger;
        lastRefusedDomain = demand.key();
        lastRefusal = new RejectTrigger.Diag5(trigger.code().text(), demand.holderSiteId(),
            Thread.currentThread().getName(), demand.worldId(), tickIndex);
    }

    public long declaredCount() {
        return declared.get();
    }

    public long grantedCount() {
        return granted.get();
    }

    public long releasedByPlanCount() {
        return releasedByPlan.get();
    }

    public long refusedCount() {
        return refused.get();
    }

    public long refusedCrossWorldCount() {
        return refusedCrossWorld.get();
    }

    public long refusedExpiredCount() {
        return refusedExpired.get();
    }

    public long refusedNoVersionCount() {
        return refusedNoVersion.get();
    }

    public long refusedDoubleHolderCount() {
        return refusedDoubleHolder.get();
    }

    public long refusedUndeclaredCount() {
        return refusedUndeclared.get();
    }

    /** How many domains this point holds for a declaring job right now. */
    public int heldCount() {
        return held.size();
    }

    /** The five elements of the newest refusal: code, site, thread, world and tick. A refusal that
     * cannot be placed after the fact is not a refusal a reader can act on. */
    public RejectTrigger.Diag5 lastRefusal() {
        return lastRefusal;
    }

    public RejectTrigger lastRefusalTrigger() {
        return lastTrigger;
    }

    public String lastRefusedDomain() {
        return lastRefusedDomain;
    }

    /** Forgets what this point holds without touching the registry: a token the registry still keeps
     * outlives a reading reset, and the next plan either re-acquires it for its holder or leaves it to
     * the sweep. */
    public void reset() {
        held.clear();
        declared.set(0L);
        granted.set(0L);
        releasedByPlan.set(0L);
        refused.set(0L);
        refusedCrossWorld.set(0L);
        refusedExpired.set(0L);
        refusedNoVersion.set(0L);
        refusedDoubleHolder.set(0L);
        refusedUndeclared.set(0L);
        lastTrigger = null;
        lastRefusal = null;
        lastRefusedDomain = "";
    }
}
