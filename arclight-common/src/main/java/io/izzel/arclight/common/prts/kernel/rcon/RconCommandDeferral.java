/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.rcon;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/** A bounded hand-off for complete remote-console commands. The calling worker only holds the
 * immutable command and a future reply; the owner thread runs prepare, dispatch and response
 * capture as one unit. The queue is global, so acceptance and dequeue order are one FIFO. */
public final class RconCommandDeferral {

    /** One command unit the owner runs. Its methods are called on the owner thread in this order;
     * the response is captured immediately after dispatch so no later command can replace it. */
    public interface OwnerUnit {
        /** Resets the host's response buffer for this command. */
        void prepare();

        /** Creates and invokes the host event and dispatches the (possibly rewritten) command. */
        void dispatch();

        /** Copies the response of the command that just ran on this owner thread. */
        String capture();

        /** Called once when prepare or dispatch threw, so a meter can close a failed unit. */
        default void failed(Throwable failure) {
        }
    }

    /** How many not-yet-completed requests the process holds at once. */
    public static final int MAX_PENDING = 64;
    /** How many UTF-8 command bytes the process holds at once. */
    public static final int MAX_PENDING_BYTES = 64 * 1024;
    /** How long a request may wait before the owner starts it. */
    public static final long DEFAULT_DEADLINE_MS = 5_000L;
    /** How many units one owner pump may run; a busy queue cannot turn one tick into a batch. */
    public static final int MAX_DRAIN_PER_PUMP = 8;

    /** A request the queue refuses before it has any side effect. */
    public static final class Rejected extends RuntimeException {
        private final boolean gateCleared;

        /** A queue refusal that happened before any command side effect. */
        public Rejected(String message) {
            this(message, false);
        }

        private Rejected(String message, boolean gateCleared) {
            super(message);
            this.gateCleared = gateCleared;
        }

        /** A refusal caused only by the real EVENT gate clearing before enqueue; the caller may
         * run the unchanged synchronous path without a double execution. */
        public static Rejected gateCleared(String message) {
            return new Rejected(message, true);
        }

        /** Whether this refusal is safe to answer with the original synchronous path. */
        public boolean gateCleared() {
            return gateCleared;
        }
    }

    private static final class Request {
        final String command;
        final OwnerUnit unit;
        final int bytes;
        final long sequence;
        final long epoch;
        boolean started;
        boolean done;
        String response;
        Throwable failure;

        Request(String command, OwnerUnit unit, int bytes, long sequence, long epoch) {
            this.command = command;
            this.unit = unit;
            this.bytes = bytes;
            this.sequence = sequence;
            this.epoch = epoch;
        }
    }

    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private long nextSequence = 1L;
    private long epoch;
    private int queuedBytes;
    private boolean shutdown;
    private boolean pumping;
    private Thread ownerThread;

    /** Blocks the calling worker until the owner has run the unit and returned its real response.
     * A worker that calls this on the owner thread is refused instead of waiting on itself. */
    public String await(String command, OwnerUnit unit) {
        return await(command, unit, DEFAULT_DEADLINE_MS, null);
    }

    /** Bounded overload used by the owner bridge and its tests: the deadline is read only while
     * the request has not started; once the owner has taken it, no timeout or cancellation is
     * reported. */
    public String await(String command, OwnerUnit unit, long deadlineMs, Runnable accepted) {
        if (command == null || unit == null) {
            throw new Rejected("null command or unit");
        }
        int bytes = command.getBytes(StandardCharsets.UTF_8).length;
        Request request;
        synchronized (lock) {
            if (shutdown) {
                throw new Rejected("deferral closed");
            }
            if (Thread.currentThread() == ownerThread) {
                throw new Rejected("reentrant owner request");
            }
            if (queue.size() >= MAX_PENDING || queuedBytes + bytes > MAX_PENDING_BYTES) {
                throw new Rejected("deferral queue full");
            }
            request = new Request(command, unit, bytes, nextSequence++, epoch);
            queue.addLast(request);
            queuedBytes += bytes;
        }
        if (accepted != null) {
            accepted.run();
        }
        long deadline = System.nanoTime() + Math.max(1L, deadlineMs) * 1_000_000L;
        synchronized (lock) {
            while (!request.done) {
                if (shutdown && !request.started) {
                    removeLocked(request);
                    throw new Rejected("deferral closed");
                }
                if (!request.started) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        removeLocked(request);
                        throw new Rejected("deferral deadline");
                    }
                    waitLocked(remaining);
                } else {
                    waitLocked(0L);
                }
            }
            if (request.failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (request.failure instanceof Error error) {
                throw error;
            }
            if (request.failure != null) {
                throw new Rejected("deferral unit failed");
            }
            return request.response;
        }
    }

    /** Runs a bounded batch of queued units on the calling owner thread. A request that is
     * already running is never re-entered; one call cannot drain a queue that keeps refilling. */
    public void pumpOwner() {
        Thread current = Thread.currentThread();
        synchronized (lock) {
            if (pumping) {
                return;
            }
            pumping = true;
            ownerThread = current;
        }
        int drained = 0;
        try {
            while (drained < MAX_DRAIN_PER_PUMP) {
                Request request;
                synchronized (lock) {
                    if (shutdown) {
                        return;
                    }
                    request = queue.peekFirst();
                    if (request == null) {
                        return;
                    }
                    request.started = true;
                }
                String response = null;
                Throwable failure = null;
                try {
                    request.unit.prepare();
                    request.unit.dispatch();
                    response = request.unit.capture();
                } catch (Throwable thrown) {
                    failure = thrown;
                    try {
                        request.unit.failed(thrown);
                    } catch (Throwable ignored) {
                        // The original command failure is the one returned to the caller.
                    }
                }
                synchronized (lock) {
                    request.response = response;
                    request.failure = failure;
                    request.done = true;
                    queue.removeFirstOccurrence(request);
                    queuedBytes -= request.bytes;
                    lock.notifyAll();
                }
                drained++;
            }
        } finally {
            synchronized (lock) {
                pumping = false;
                ownerThread = null;
                lock.notifyAll();
            }
        }
    }

    /** Rejects every request the owner has not started; a started request is left to finish and
     * is never reported as cancelled. */
    public void terminateQueued() {
        synchronized (lock) {
            shutdown = true;
            rejectPendingLocked();
        }
    }

    /** Rejects queued requests for a reset but leaves the hand-off open for new requests. */
    public void resetQueued() {
        synchronized (lock) {
            epoch++;
            rejectPendingLocked();
        }
    }

    /** True when no request is queued or running. */
    public boolean idle() {
        synchronized (lock) {
            return queue.isEmpty() && !pumping;
        }
    }

    /** The reset generation of the hand-off; a started request keeps the generation it entered. */
    public long epoch() {
        synchronized (lock) {
            return epoch;
        }
    }

    /** The current bounded queue depth. */
    public int queued() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** The current bounded bytes held by the queue. */
    public int queuedBytes() {
        synchronized (lock) {
            return queuedBytes;
        }
    }

    private void rejectPendingLocked() {
        var iterator = queue.iterator();
        while (iterator.hasNext()) {
            Request request = iterator.next();
            if (request.started) {
                continue;
            }
            request.failure = new Rejected("deferral reset");
            request.done = true;
            queuedBytes -= request.bytes;
            iterator.remove();
        }
        lock.notifyAll();
    }

    private void removeLocked(Request request) {
        if (queue.removeFirstOccurrence(request)) {
            queuedBytes -= request.bytes;
        }
        lock.notifyAll();
    }

    private void waitLocked(long nanos) {
        try {
            if (nanos <= 0L) {
                lock.wait();
            } else {
                lock.wait(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Rejected("deferral interrupted");
        }
    }
}
