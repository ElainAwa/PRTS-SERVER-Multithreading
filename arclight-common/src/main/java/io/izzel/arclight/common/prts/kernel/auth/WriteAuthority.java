/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger.ConflictClass;
import io.izzel.arclight.common.prts.kernel.auth.WriteAttempt.WriteOp;

/** The one decision point every world write passes through. The decision is total: every attempt
 * leaves with exactly one of the three dispositions, so the accounting closure of the ledger holds
 * by construction. */
public final class WriteAuthority {

    private final OwnerRegistry owners;
    private final IntentQueue intents;
    private final WriteLedger ledger;
    private final BooleanSupplier enforceUnregisteredWrites;
    private final IntSupplier retryBudget;
    private final AtomicLong nextAttemptId = new AtomicLong(1L);

    /** Creates the decision point. */
    public WriteAuthority(OwnerRegistry owners, IntentQueue intents, WriteLedger ledger,
                          BooleanSupplier enforceUnregisteredWrites, IntSupplier retryBudget) {
        this.owners = owners;
        this.intents = intents;
        this.ledger = ledger;
        this.enforceUnregisteredWrites = enforceUnregisteredWrites;
        this.retryBudget = retryBudget;
    }

    public long nextAttemptId() {
        return nextAttemptId.getAndIncrement();
    }

    /** Judges one attempt. */
    public WriteVerdict authorize(WriteAttempt attempt) {
        ledger.noteAttempt(attempt);
        WriteVerdict verdict;
        try {
            verdict = decide(attempt);
        } catch (Throwable thrown) {
            // No verdict is coming: the attempt leaves flight and the closure reports it.
            ledger.noteUnjudged(attempt);
            throw thrown;
        }
        ledger.noteVerdict(attempt, verdict);
        return verdict;
    }

    private WriteVerdict decide(WriteAttempt attempt) {
        if (attempt.wallClockRead()) {
            return WriteVerdict.deny(RejectCode.PLAN_CLOCK_READ, ConflictClass.REJECT,
                RejectTrigger.PLAN_WALL_CLOCK_READ.description());
        }
        if (attempt.op() == WriteOp.READ) {
            return WriteVerdict.grant("a read takes no write right");
        }
        if (attempt.holderKind() == HolderKind.KERNEL) {
            return WriteVerdict.grant("the kernel holder writes its own domain");
        }
        if (attempt.holderKind() == HolderKind.UNREGISTERED) {
            return unregisteredWrite(attempt);
        }
        if (!attempt.siteAdmitted()) {
            return WriteVerdict.deny(RejectCode.NATIVE_UNDECLARED, ConflictClass.REJECT,
                RejectTrigger.SITE_DECLARATION_MISSING.description());
        }
        if (attempt.crossWorld()) {
            return queueIntent(attempt, RejectCode.CROSS_WORLD_WRITE_DENIED,
                RejectTrigger.CROSS_WORLD_DIRECT_WRITE.description(), "xworld");
        }
        if (!attempt.versionCarried()) {
            return WriteVerdict.deny(RejectCode.VERSION_MISMATCH, ConflictClass.REJECT,
                "a write without an expected version does not exist");
        }
        long liveWorldEpoch = owners.worldEpochOf(attempt.worldId());
        if (liveWorldEpoch > 0L && liveWorldEpoch != attempt.worldEpoch()) {
            return WriteVerdict.deny(RejectCode.WORLD_LIFECYCLE_DENIED, ConflictClass.REJECT,
                RejectTrigger.WORLD_LIFECYCLE_VIOLATION.description());
        }
        OwnerToken declared = attempt.declaredOwner();
        OwnerToken live = owners.lookup(attempt.worldId(), attempt.level(), attempt.domainId())
            .orElse(null);
        OwnerToken token;
        if (declared != null) {
            if (!declared.worldId().equals(attempt.worldId())
                || declared.level() != attempt.level()
                || !declared.domainId().equals(attempt.domainId())
                || declared.holderKind() != attempt.holderKind()
                || !declared.holderSiteId().equals(attempt.holderSiteId())) {
                return WriteVerdict.deny(RejectCode.WRITE_DENIED_NOT_OWNER, ConflictClass.DEGRADE,
                    RejectTrigger.OWNER_MISMATCH.description());
            }
            // A declared credential is a record of what the registry installed, not a right of its
            // own: a released, retired or replaced one names no live right and cannot grant.
            if (live == null) {
                return WriteVerdict.deny(RejectCode.WRITE_DENIED_NOT_OWNER, ConflictClass.DEGRADE,
                    RejectTrigger.OWNER_MISMATCH.description());
            }
            if (live.holderKind() != declared.holderKind()
                || !live.holderSiteId().equals(declared.holderSiteId())
                || live.epoch() != declared.epoch()
                || live.planSequence() != declared.planSequence()
                || live.worldEpoch() != declared.worldEpoch()
                || live.expectedVersion() != declared.expectedVersion()
                || live.holdStartTick() != declared.holdStartTick()
                || live.expireTick() != declared.expireTick()) {
                return WriteVerdict.deny(RejectCode.WRITE_DENIED_NOT_OWNER, ConflictClass.DEGRADE,
                    RejectTrigger.OWNER_MISMATCH.description());
            }
            if (live.expectedVersion() != attempt.expectedVersion()) {
                return WriteVerdict.deny(RejectCode.VERSION_MISMATCH, ConflictClass.REJECT,
                    RejectTrigger.VERSION_SLOT_MISMATCH.description());
            }
            token = live;
        } else {
            if (live == null || !live.holderSiteId().equals(attempt.holderSiteId())) {
                return WriteVerdict.deny(RejectCode.WRITE_DENIED_NOT_OWNER, ConflictClass.DEGRADE,
                    RejectTrigger.OWNER_MISMATCH.description());
            }
            token = live;
        }
        if (token.worldEpoch() > 0L && token.worldEpoch() != attempt.worldEpoch()) {
            return WriteVerdict.deny(RejectCode.WORLD_LIFECYCLE_DENIED, ConflictClass.REJECT,
                RejectTrigger.WORLD_LIFECYCLE_VIOLATION.description());
        }
        if (declared != null && attempt.planOrder() > 0L && token.planSequence() > 0L
            && token.planSequence() != attempt.planOrder()) {
            // The declaration was frozen for a plan the registry has since moved past; the value it
            // carries belongs to that older plan and not to the write in front of the decision point.
            return WriteVerdict.deny(RejectCode.VERSION_MISMATCH, ConflictClass.REJECT,
                RejectTrigger.VERSION_SLOT_MISMATCH.description());
        }
        if (token.expiredAt(attempt.tickIndex())) {
            owners.reclaimExpired(attempt.tickIndex());
            return WriteVerdict.deny(RejectCode.VERSION_MISMATCH, ConflictClass.REJECT,
                RejectTrigger.TOKEN_EXPIRED.description());
        }
        if (token.expectedVersion() != attempt.expectedVersion()) {
            return WriteVerdict.deny(RejectCode.VERSION_MISMATCH, ConflictClass.REJECT,
                RejectTrigger.VERSION_SLOT_MISMATCH.description());
        }
        if (!attempt.declaredDomains().containsAll(attempt.observedDomains())) {
            return WriteVerdict.deny(RejectCode.WRITE_DENIED_NOT_OWNER, ConflictClass.DEGRADE,
                RejectTrigger.DOMAIN_SET_NOT_SPLIT.description());
        }
        if (attempt.lifecycleChange() && !attempt.lifecycleOwner()) {
            return WriteVerdict.deny(RejectCode.WORLD_LIFECYCLE_DENIED, ConflictClass.REJECT,
                RejectTrigger.WORLD_LIFECYCLE_VIOLATION.description());
        }
        return WriteVerdict.grant("the holder owns the domain and the version matches",
            retryBudget.getAsInt());
    }

    private WriteVerdict unregisteredWrite(WriteAttempt attempt) {
        if (enforceUnregisteredWrites.getAsBoolean()) {
            return WriteVerdict.deny(RejectCode.WRITE_DENIED_NOT_OWNER, ConflictClass.REJECT,
                "an unregistered writer holds no world write right");
        }
        if (!attempt.versionCarried()) {
            return WriteVerdict.deny(RejectCode.VERSION_MISMATCH, ConflictClass.REJECT,
                "an intent has to carry the version it expects to find");
        }
        return queueIntent(attempt, null, RejectTrigger.UNREGISTERED_WRITE.description(), "xdomain");
    }

    private WriteVerdict queueIntent(WriteAttempt attempt, RejectCode code, String reason,
                                     String waitPoint) {
        long intentId = intents.nextIntentId();
        WriteIntent intent = new WriteIntent(intentId, attempt.declaredWorldId(), attempt.worldId(),
            attempt.domainId(), attempt.expectedVersion(), attempt.worldEpoch(), attempt.planOrder(),
            "arena:" + attempt.attemptId(), waitPoint, attempt.holderSiteId());
        IntentQueue.EnqueueResult result = intents.enqueue(intent);
        if (!result.accepted()) {
            return WriteVerdict.deny(RejectCode.QUEUE_CAP_EXCEEDED, ConflictClass.SKIP,
                RejectTrigger.INTENT_QUEUE_FULL.description());
        }
        return WriteVerdict.intent(code, reason, intentId, retryBudget.getAsInt());
    }
}
