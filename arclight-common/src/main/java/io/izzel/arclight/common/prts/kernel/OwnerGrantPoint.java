/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;

/** Turns the write right a frozen job declares into the one credential a writer may hold. There is no
 * entry that hands a right out by itself: the only call takes the graph the planning period froze, and
 * a token is minted only for a demand a declaration carried in that graph. The point keeps the right
 * while the declaring job is in the newest plan and releases it when the job is gone; the registry
 * sweep reclaims it when the hold window runs out. Off, it grants nothing, releases nothing and counts
 * nothing, so the registry sees exactly what it saw before. */
public final class OwnerGrantPoint {

    private final OwnerRegistry owners;
    private final WriteVersionSlots versions;
    private final ToLongFunction<String> worldEpochs;
    private final Map<OwnershipDomain, OwnerToken> held = new HashMap<>();

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
        this(owners, versions, worldId -> 0L);
    }

    /** Creates the point with the world incarnation a granted token is bound to. A supplier that
     * answers no incarnation leaves the token unbound, which is the shape it had before the binding
     * existed. */
    public OwnerGrantPoint(OwnerRegistry owners, WriteVersionSlots versions,
                           ToLongFunction<String> worldEpochs) {
        this.owners = owners;
        this.versions = versions;
        this.worldEpochs = worldEpochs == null ? worldId -> 0L : worldEpochs;
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
     * declarations of that tick and of the tokens the ticks before it left behind. One node's demand
     * set is taken as a whole: the demands are visited in a stable key order, every one of them is
     * checked before the first is installed, and a refused set releases the reservations it made. */
    public void apply(JobGraph graph, long tickIndex) {
        if (!enabled) {
            return;
        }
        Set<OwnershipDomain> declaredNow = new HashSet<>();
        List<JobGraph.Node> nodes = graph == null ? List.of() : graph.nodes();
        for (JobGraph.Node node : nodes) {
            List<JobDeclaration.OwnerDemand> demands = new ArrayList<>();
            for (JobDeclaration.OwnerDemand demand : node.ownerDemands()) {
                if (!demands.contains(demand)) {
                    demands.add(demand);
                }
            }
            if (demands.isEmpty()) {
                continue;
            }
            demands.sort(Comparator.comparing(JobDeclaration.OwnerDemand::key)
                .thenComparing(demand -> demand.holderKind().name())
                .thenComparing(JobDeclaration.OwnerDemand::holderSiteId));
            declared.addAndGet(demands.size());
            hold(node, demands, graph.planSequence(), tickIndex, declaredNow);
        }
        releaseUndeclared(declaredNow);
    }

    private void hold(JobGraph.Node node, List<JobDeclaration.OwnerDemand> demands,
                      long planSequence, long tickIndex, Set<OwnershipDomain> declaredNow) {
        long[] carried = new long[demands.size()];
        for (int index = 0; index < demands.size(); index++) {
            JobDeclaration.OwnerDemand demand = demands.get(index);
            RejectTrigger invalid = check(node, demand);
            if (invalid == null) {
                carried[index] = versions.carried(demand.worldId(), demand.level(),
                    demand.domainId());
                if (carried[index] == WriteVersionSlots.NOT_CARRIED) {
                    invalid = RejectTrigger.VERSION_SLOT_MISMATCH;
                }
            }
            if (invalid != null) {
                countRefusal(invalid);
                refuse(invalid, demand, tickIndex);
                return;
            }
        }
        List<OwnerToken> candidates = new ArrayList<>();
        List<OwnershipDomain> domains = new ArrayList<>();
        long newInstalls = 0L;
        for (int index = 0; index < demands.size(); index++) {
            JobDeclaration.OwnerDemand demand = demands.get(index);
            long version = carried[index];
            long worldEpoch = worldEpochOf(demand.worldId());
            OwnershipDomain domain = new OwnershipDomain(demand.worldId(), demand.level(),
                demand.domainId());
            OwnerToken previous = owners.lookup(demand.worldId(), demand.level(), demand.domainId())
                .orElse(null);
            long epoch = previous == null ? owners.mintEpoch() : previous.epoch();
            OwnerToken candidate = new OwnerToken(demand.worldId(), demand.level(),
                demand.domainId(), epoch, version, tickIndex, tickIndex + demand.holdTicks(),
                demand.holderKind(), demand.holderSiteId(), planSequence, worldEpoch);
            candidates.add(candidate);
            domains.add(domain);
            if (previous == null) {
                newInstalls++;
            }
        }
        if (!owners.acquireAll(candidates)) {
            refusedDoubleHolder.incrementAndGet();
            refuse(RejectTrigger.OWNER_MISMATCH, demands.get(0), tickIndex);
            return;
        }
        for (int index = 0; index < candidates.size(); index++) {
            held.put(domains.get(index), candidates.get(index));
        }
        granted.addAndGet(newInstalls);
        for (JobDeclaration.OwnerDemand demand : demands) {
            declaredNow.add(new OwnershipDomain(demand.worldId(), demand.level(),
                demand.domainId()));
        }
    }

    private RejectTrigger check(JobGraph.Node node, JobDeclaration.OwnerDemand demand) {
        if (!demand.worldId().equals(node.worldId())) {
            return RejectTrigger.CROSS_WORLD_DIRECT_WRITE;
        }
        if (!declared(node, demand)) {
            return RejectTrigger.DOMAIN_SET_NOT_SPLIT;
        }
        if (demand.holdTicks() <= 0) {
            return RejectTrigger.TOKEN_EXPIRED;
        }
        return null;
    }

    private void countRefusal(RejectTrigger trigger) {
        switch (trigger) {
            case CROSS_WORLD_DIRECT_WRITE -> refusedCrossWorld.incrementAndGet();
            case DOMAIN_SET_NOT_SPLIT -> refusedUndeclared.incrementAndGet();
            case TOKEN_EXPIRED -> refusedExpired.incrementAndGet();
            case VERSION_SLOT_MISMATCH -> refusedNoVersion.incrementAndGet();
            default -> {
            }
        }
    }

    private long worldEpochOf(String worldId) {
        return Math.max(0L, worldEpochs.applyAsLong(worldId));
    }

    private void releaseUndeclared(Set<OwnershipDomain> declaredNow) {
        Iterator<Map.Entry<OwnershipDomain, OwnerToken>> iterator = held.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<OwnershipDomain, OwnerToken> entry = iterator.next();
            OwnershipDomain domain = entry.getKey();
            if (declaredNow.contains(domain)) {
                continue;
            }
            OwnerToken token = owners.lookup(domain.worldId(), domain.level(), domain.domainId())
                .orElse(null);
            if (token != null && token.equals(entry.getValue())
                && owners.release(domain, token.holderSiteId())) {
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

    /** Ends this point's grants: every right it installed is released, so a reset reading cannot be
     * followed by a write that still names the retired credential. */
    public void reset() {
        for (Map.Entry<OwnershipDomain, OwnerToken> entry : held.entrySet()) {
            OwnershipDomain domain = entry.getKey();
            OwnerToken token = owners.lookup(domain.worldId(), domain.level(), domain.domainId())
                .orElse(null);
            if (token != null && token.equals(entry.getValue())) {
                owners.release(domain, token.holderSiteId());
            }
        }
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
