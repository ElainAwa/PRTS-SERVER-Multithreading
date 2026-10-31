/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/** The managed synchronous read context and its materialized-unloaded counter. A read is counted
 * only when an explicit context names its world/chunk and a synchronous chunk read actually returns
 * an absent chunk; rejected, registered-only, loaded-snapshot and source-unknown reads are not
 * counted. The formal field is published only by a complete window, otherwise it is absent. */
public final class MaterializedUnloadedReadings {

    /** One explicit managed read context; owner is the thread name that created it. */
    public record Context(long readId, long epoch, String worldId, long chunkPos, String category,
                          String owner) {
    }

    public enum Outcome {
        COMPLETE,
        REJECTED,
        LOADED_SNAPSHOT,
        NO_WORLD,
        SOURCE_UNAVAILABLE
    }

    private static final int CONTEXT_LIMIT = 256;
    private static final Map<Long, Context> CONTEXTS = new HashMap<>();
    private static final Map<String, Long> BY_TARGET = new HashMap<>();
    private static final Set<Long> COUNTED = new HashSet<>();
    private static final ArrayDeque<Long> RING = new ArrayDeque<>();
    private static long nextReadId = 1L;
    private static long epoch;
    private static int expectedReads;
    private static int concludedReads;
    private static long materialized;
    private static boolean windowOpen;
    private static boolean sourceValid;
    private static boolean sourceUnavailable;
    private static boolean loss;
    private static boolean truncated;
    private static boolean reset;

    private MaterializedUnloadedReadings() {
    }

    public static synchronized long beginWindow(int expected) {
        clearWindow();
        expectedReads = Math.max(0, expected);
        windowOpen = true;
        sourceValid = true;
        return epoch;
    }

    public static synchronized long begin(String worldId, long chunkPos, String category) {
        long readId = nextReadId++;
        Context context = new Context(readId, epoch, worldId, chunkPos, category,
            Thread.currentThread().getName());
        CONTEXTS.put(readId, context);
        RING.addLast(readId);
        while (RING.size() > CONTEXT_LIMIT) {
            long evicted = RING.removeFirst();
            CONTEXTS.remove(evicted);
            truncated = true;
        }
        BY_TARGET.put(key(worldId, chunkPos), readId);
        return readId;
    }

    public static synchronized void end(long readId, Outcome outcome) {
        Context context = CONTEXTS.get(readId);
        if (context == null) {
            loss = true;
            return;
        }
        if (context.epoch() != epoch) {
            loss = true;
            return;
        }
        concludedReads++;
        if (outcome != Outcome.COMPLETE && COUNTED.remove(readId)) {
            materialized = Math.max(0L, materialized - 1L);
        }
        if (outcome == Outcome.NO_WORLD) {
            return;
        }
        if (outcome == Outcome.SOURCE_UNAVAILABLE) {
            sourceUnavailable = true;
            return;
        }
        if (outcome == Outcome.REJECTED || outcome == Outcome.LOADED_SNAPSHOT) {
            return;
        }
    }

    /** Called from the synchronous cache receipt point; only an absent chunk that was returned
     * under an active same-target context is counted, once per read. */
    public static synchronized void onSynchronousRead(String worldId, long chunkPos, boolean load,
                                                      boolean wasAbsent, boolean returned) {
        if (!load || !wasAbsent || !returned) {
            return;
        }
        Long readId = BY_TARGET.get(key(worldId, chunkPos));
        if (readId == null) {
            return;
        }
        Context context = CONTEXTS.get(readId);
        if (context == null || context.epoch() != epoch) {
            loss = true;
            return;
        }
        if (COUNTED.add(readId)) {
            materialized++;
        }
    }

    public static synchronized void sourceUnavailable() {
        sourceUnavailable = true;
    }

    public static synchronized void truncation() {
        truncated = true;
    }

    public static synchronized void loss() {
        loss = true;
    }

    public static synchronized boolean resetDetected() {
        return reset;
    }

    /** Publishes only a complete, source-valid, lossless, untruncated and non-reset window. */
    public static synchronized OptionalLong publishedValue() {
        if (!windowOpen || reset || truncated || loss || sourceUnavailable
            || !sourceValid || concludedReads != expectedReads) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(materialized);
    }

    public static synchronized boolean windowOpen() {
        return windowOpen;
    }

    /** A published positive count is the failure condition of the managed read window. */
    public static synchronized boolean failOnPositive() {
        return publishedValue().isPresent() && publishedValue().getAsLong() > 0L;
    }

    public static synchronized void markReset() {
        reset = true;
    }

    public static synchronized void reset() {
        clearWindow();
        CONTEXTS.clear();
        BY_TARGET.clear();
        COUNTED.clear();
        RING.clear();
        nextReadId = 1L;
        epoch = 0L;
    }

    private static void clearWindow() {
        expectedReads = 0;
        concludedReads = 0;
        materialized = 0L;
        windowOpen = false;
        sourceValid = true;
        sourceUnavailable = false;
        loss = false;
        truncated = false;
        reset = false;
        epoch++;
    }

    private static String key(String worldId, long chunkPos) {
        return worldId + '|' + chunkPos;
    }
}
