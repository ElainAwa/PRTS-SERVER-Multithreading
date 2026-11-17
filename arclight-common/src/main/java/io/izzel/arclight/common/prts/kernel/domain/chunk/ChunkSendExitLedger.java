/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The send exit of one chunk holder generation: a chunk may queue only when its completion state
 * really reached SEND_READY, an acknowledgement publishes once, and a cancelled or refused exit
 * never publishes. It also keeps the cold deadline started at the first input arrival, which a
 * batch or a retry never resets. */
public final class ChunkSendExitLedger {

    /** What the exit of one chunk stands at. */
    public enum ExitState {
        BLOCKED,
        QUEUED,
        ACKED,
        REFUSED,
        CANCELLED
    }

    /** The outcome of one queue, ack, cancel or fail decision. */
    public record Result(ExitState state, boolean changed, String reason) {
    }

    /** The three cold deadlines of one request, in ticks, fixed at the first input arrival. */
    public record ColdDeadlines(long firstInputTick, long minDeadline, long maxDeadline,
                                long sendDeadline) {
    }

    private final ChunkCompletionState completion;
    private final Map<String, ColdDeadlines> deadlines = new LinkedHashMap<>();
    private ExitState state = ExitState.BLOCKED;
    private long queued;
    private long published;
    private long refused;
    private long cancelled;

    public ChunkSendExitLedger(ChunkCompletionState completion) {
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    /** Queues the send only when the completion state observed SEND_READY. */
    public synchronized Result queue() {
        if (state == ExitState.QUEUED || state == ExitState.ACKED) {
            return new Result(state, false, "already-queued");
        }
        if (!completion.observed(ChunkCompletionLayer.SEND_READY)) {
            state = ExitState.BLOCKED;
            refused++;
            return new Result(state, false, "send-ready-not-observed");
        }
        state = ExitState.QUEUED;
        queued++;
        return new Result(state, true, "queued");
    }

    /** Publishes once the host acknowledged the queued send; only then is the exit complete. */
    public synchronized Result ack() {
        if (state != ExitState.QUEUED) {
            return new Result(state, false, "not-queued");
        }
        state = ExitState.ACKED;
        published++;
        return new Result(state, true, "acked");
    }

    /** Cancels a queued or blocked exit; a cancelled exit never publishes. */
    public synchronized Result cancel() {
        if (state == ExitState.ACKED) {
            return new Result(state, false, "already-published");
        }
        state = ExitState.CANCELLED;
        cancelled++;
        return new Result(state, true, "cancelled");
    }

    /** Fails the exit; a failed exit never publishes. */
    public synchronized Result fail() {
        if (state == ExitState.ACKED) {
            return new Result(state, false, "already-published");
        }
        state = ExitState.REFUSED;
        refused++;
        return new Result(state, true, "failed");
    }

    public synchronized ExitState state() {
        return state;
    }

    public synchronized long queued() {
        return queued;
    }

    public synchronized long published() {
        return published;
    }

    public synchronized long refused() {
        return refused;
    }

    public synchronized long cancelled() {
        return cancelled;
    }

    /** Starts the cold deadlines at the first input arrival; a repeated arrival keeps the first. */
    public synchronized ColdDeadlines coldStart(String requestId, long firstInputTick,
                                                long minTicks, long maxTicks, long sendTicks) {
        Objects.requireNonNull(requestId, "requestId");
        return deadlines.computeIfAbsent(requestId, key -> new ColdDeadlines(firstInputTick,
            firstInputTick + minTicks, firstInputTick + maxTicks, firstInputTick + sendTicks));
    }

    /** A batch or a retry never resets a cold deadline; it returns the deadlines already fixed. */
    public synchronized ColdDeadlines coldOnBatchOrRetry(String requestId) {
        return deadlines.get(requestId);
    }

    /** The fixed deadlines of one request, or null when the input never arrived. */
    public synchronized ColdDeadlines cold(String requestId) {
        return deadlines.get(requestId);
    }
}
