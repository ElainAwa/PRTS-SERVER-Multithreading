/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.domain.ai.AiCommitSeat;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;

/** Host-side AI candidate commit: version-checked, once per actor, stale/duplicate visible. */
public final class AiOwnerCommit {

    public enum Decision {
        COMMITTED, STALE, DUPLICATE, FALLBACK, INVALID
    }

    public record Candidate(long actorId, long targetId, long snapshotVersion) {
    }

    private final long expectedVersion;
    private boolean committed;

    public AiOwnerCommit(long expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public synchronized Decision commit(Candidate candidate) {
        if (candidate == null || candidate.actorId() < 0L || candidate.snapshotVersion() != expectedVersion) {
            return candidate == null ? Decision.INVALID : Decision.STALE;
        }
        if (committed) return Decision.DUPLICATE;
        int result = AiOwnershipBridge.commit(candidate.actorId(), candidate.targetId(),
            candidate.snapshotVersion(), 1L);
        if (result == AiOwnershipBridge.STALE) return Decision.STALE;
        if (result != AiOwnershipBridge.COMMITTED) return Decision.FALLBACK;
        committed = true;
        return Decision.COMMITTED;
    }

    public synchronized boolean committed() {
        return committed;
    }

    /** Commits through the real CommitLog and appends the AI intent; off/absent path keeps old
     * commit behaviour. The log verdict gates the local once flag. */
    public synchronized Decision commit(Candidate candidate, AiCommitSeat.Seat seat,
                                        CommitLog log, IntentQueue intents, long lifecycle) {
        Decision local = commit(candidate);
        if (local != Decision.COMMITTED || log == null || seat == null) {
            return local;
        }
        var batch = AiCommitSeat.batch(seat, candidate.snapshotVersion(), 7L, 1);
        var verdict = log.reach(batch, seat.planSequence(), seat.position(), lifecycle);
        if (!verdict.logged()) {
            if (Boolean.getBoolean("arclight.prts.aiDiag")) {
                System.out.println("[PRTS] ai-diag commit_verdict code=" + verdict.code()
                    + " disposition=" + verdict.disposition() + " sequence=" + verdict.sequence()
                    + " position=" + verdict.position());
            }
            return Decision.INVALID;
        }
        if (intents != null) {
            intents.enqueue(WriteIntent.draft(1L, seat.worldId(), seat.worldId(), seat.domainId(),
                candidate.snapshotVersion(), 1L, seat.nodeKey(), "ai", seat.site()));
        }
        return Decision.COMMITTED;
    }
}
