/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteAttempt;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVerdict;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityObjectGenerations;
import io.izzel.arclight.common.prts.kernel.domain.entity.OwnerContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** The real registry lease one deferred walk body runs under: the row's object domain is acquired
 * from the process owner registry before the worker body and released after it, so two bodies of one
 * row can never run under one domain. The lease is a diagnostic contract until the batch is wired to
 * the plan's commit chain; a refusal is terminal and never falls back to the host tick. */
public final class OwnerBodyLease {

    /** The diagnostic switch; off in every shipped build. */
    public static final String PROPERTY = "arclight.prts.ownerBatchLease";
    /** The diagnostic switch for the write-authority decision on the same row; off by default. */
    public static final String AUTHORITY_PROPERTY = "arclight.prts.ownerBatchAuthority";

    private static final String ENTITY_STATE_PREFIX = "entity-state:";
    private static final java.util.concurrent.atomic.AtomicLong EPOCH =
        new java.util.concurrent.atomic.AtomicLong();

    private final OwnerRegistry registry;
    private final OwnerRegistry.OwnershipDomain domain;
    private final String siteId;

    private OwnerBodyLease(OwnerRegistry registry, OwnerRegistry.OwnershipDomain domain,
                           String siteId) {
        this.registry = registry;
        this.domain = domain;
        this.siteId = siteId;
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Acquires the real object-domain lease for one row; a refusal is terminal. */
    public static OwnerBodyLease acquire(ServerLevel level, Entity entity, long tick,
                                         long planOrder) {
        StageCounters.note("ownerBatchLeaseAcquire");
        if (!enabled()) {
            return null;
        }
        OwnerContext context = KernelModule.instance().ownerContext();
        OwnerRegistry registry = context.registry();
        String worldId = level.dimension().location().toString();
        long uuidHigh = entity.getUUID().getMostSignificantBits();
        long uuidLow = entity.getUUID().getLeastSignificantBits();
        long generation = EntityObjectGenerations.shared()
            .generationOf(worldId, uuidHigh, uuidLow);
        String domainId = ENTITY_STATE_PREFIX + uuidHigh + ":" + uuidLow + ":" + generation;
        // The kernel's planning period is off in this diagnostic window, so the slot would answer
        // NOT_CARRIED; the opt-in lease grants it locally instead of inventing a version.
        context.versions().refresh(true);
        long expectedVersion = context.versions().grant(worldId, WriteLevel.OBJECT, domainId);
        long epoch = EPOCH.incrementAndGet();
        OwnerToken token = new OwnerToken(worldId, WriteLevel.OBJECT, domainId, epoch,
            expectedVersion, tick, tick + 2L, HolderKind.REGISTERED,
            context.primarySite().siteId(), 0L, registry.worldEpochOf(worldId));
        if (!registry.acquire(token)) {
            StageCounters.note("ownerBatchLeaseRefusal");
            throw new IllegalStateException("owner batch lease refused");
        }
        StageCounters.note("ownerBatchLeaseHeld");
        io.izzel.arclight.common.prts.kernel.auth.OwnerUnitPermit.bindLease(domainId);
        OwnerBodyLease lease =
            new OwnerBodyLease(registry, token.domain(), context.primarySite().siteId());
        if (Boolean.getBoolean(AUTHORITY_PROPERTY)) {
            WriteAttempt attempt = WriteAttempt
                .builder(KernelModule.instance().authority().nextAttemptId(), worldId,
                    context.primarySite().siteId())
                .holder(HolderKind.REGISTERED, context.primarySite().siteId(),
                    Thread.currentThread().getName())
                .target(WriteLevel.OBJECT, domainId)
                .domains(java.util.Set.of(domainId), java.util.Set.of())
                .version(expectedVersion, tick, planOrder)
                .worldEpoch(registry.worldEpochOf(worldId))
                .admitted(true)
                .declaredOwner(token)
                .build();
            WriteVerdict verdict = KernelModule.instance().authority().authorize(attempt);
            if (verdict.disposition() != WriteDisposition.GRANT) {
                StageCounters.note("ownerBatchAuthorityDeny");
                lease.release();
                throw new IllegalStateException("owner batch authority refused: "
                    + verdict.disposition() + " " + verdict.reason());
            }
            StageCounters.note("ownerBatchAuthorityGrant");
        }
        return lease;
    }

    /** Releases the lease; a refusal here is terminal because the domain must not stay installed. */
    public void release() {
        if (!registry.release(domain, siteId)) {
            StageCounters.note("ownerBatchLeaseReleaseRefusal");
            throw new IllegalStateException("owner batch lease release refused");
        }
        StageCounters.note("ownerBatchLeaseReleased");
    }
}