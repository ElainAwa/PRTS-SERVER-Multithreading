/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk.save;

import java.util.LinkedHashMap;
import java.util.Map;

/** The six-level watermark of one chunk revision: APPLIED, ENCODED, OS_ACCEPTED, PERSISTED,
 * DURABLE and RESTART_VERIFIED only rise with their evidence; a disk-full, short-write, I/O or
 * cancelled save fails honestly, keeps its unfinished obligation and never reports DURABLE. */
public final class SaveWatermarkLedger {

    /** The durable claim of one revision, from the applied work to the restart readback. */
    public enum Level {
        APPLIED,
        ENCODED,
        OS_ACCEPTED,
        PERSISTED,
        DURABLE,
        RESTART_VERIFIED
    }

    /** Why a save did not reach its next level; a failure never downgrades a reached level. */
    public enum Failure {
        NONE,
        DISK_FULL,
        SHORT_WRITE,
        IO_ERROR,
        CANCELLED
    }

    /** The watermark and the retained obligation of one chunk. */
    public record State(Level level, Failure failure, boolean obligationRetained, String evidence) {
    }

    private final Map<Long, State> chunks = new LinkedHashMap<>();
    private long advanced;
    private long failed;
    private long refusedDowngrade;
    private long verified;

    /** Advances exactly one level with its evidence; a jump, a failure or a lower level is refused. */
    public synchronized boolean advance(long chunkPos, Level level, String evidence) {
        State current = chunks.getOrDefault(chunkPos, new State(null, Failure.NONE, false, ""));
        if (current.failure() != Failure.NONE) {
            failed++;
            return false;
        }
        if (current.level() != null && level.ordinal() <= current.level().ordinal()) {
            refusedDowngrade++;
            return false;
        }
        if (current.level() != null && level.ordinal() != current.level().ordinal() + 1) {
            refusedDowngrade++;
            return false;
        }
        chunks.put(chunkPos, new State(level, Failure.NONE, false, evidence == null ? "" : evidence));
        advanced++;
        return true;
    }

    /** Records a failed save: the unfinished obligation stays and DURABLE is never claimed. */
    public synchronized void fail(long chunkPos, Failure failure) {
        State current = chunks.getOrDefault(chunkPos, new State(null, Failure.NONE, false, ""));
        if (current.level() != null && current.level().ordinal() >= Level.DURABLE.ordinal()) {
            refusedDowngrade++;
            return;
        }
        chunks.put(chunkPos, new State(current.level(), failure, true, "failed"));
        failed++;
    }

    /** Confirms the restart readback; only a durable chunk with matching evidence can verify. */
    public synchronized boolean restartVerified(long chunkPos, boolean readbackMatches) {
        State current = chunks.get(chunkPos);
        if (current == null || !durable(chunkPos) || !readbackMatches) {
            failed++;
            return false;
        }
        chunks.put(chunkPos, new State(Level.RESTART_VERIFIED, Failure.NONE, false, "restart"));
        verified++;
        return true;
    }

    public synchronized Level level(long chunkPos) {
        State state = chunks.get(chunkPos);
        return state == null ? null : state.level();
    }

    public synchronized boolean durable(long chunkPos) {
        State state = chunks.get(chunkPos);
        return state != null && state.failure() == Failure.NONE && state.level() != null
            && state.level().ordinal() >= Level.DURABLE.ordinal();
    }

    public synchronized boolean obligationRetained(long chunkPos) {
        State state = chunks.get(chunkPos);
        return state != null && state.obligationRetained();
    }

    public synchronized long advanced() {
        return advanced;
    }

    public synchronized long failed() {
        return failed;
    }

    public synchronized long refusedDowngrade() {
        return refusedDowngrade;
    }

    public synchronized long verified() {
        return verified;
    }
}
