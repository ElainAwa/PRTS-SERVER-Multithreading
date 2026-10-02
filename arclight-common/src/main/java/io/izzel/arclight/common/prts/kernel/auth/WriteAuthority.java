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
        OwnerToken token = owners.lookup(attempt.worldId(), attempt.level(), attempt.domainId())
            .orElse(null);
        if (token == null || !token.holderSiteId().equals(attempt.holderSiteId())) {
            return WriteVerdict.deny(RejectCode.WRITE_DENIED_NOT_OWNER, ConflictClass.DEGRADE,
                RejectTrigger.OWNER_MISMATCH.description());
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
