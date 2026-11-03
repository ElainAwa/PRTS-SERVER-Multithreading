/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsPipelineRows;

import java.util.LinkedHashMap;
import java.util.Map;

/** The bounded worker-side meter of generation and light task time. It is fed through the existing
 * pipeline mailbox tap (the mailbox object, the name and the task nanoseconds); the world is read
 * from the existing mailbox placement and the epoch is pinned by the window the owner opened. A
 * report that is negative, too long to be one task, non-target, unplaced, of a world outside the
 * window or beyond the bucket bound is rejected instead of entering a bucket. The owner drains the
 * window at its existing budget boundary and a drained window is never merged: a later report is
 * rejected as closed. This class is internal: no formal readout key and no new tap. */
public final class WorldResChildMeter {

    public static final String WORLDGEN = "worldgen";

    public static final String LIGHT = "light";

    /** How many worlds one window can hold; a world beyond it is rejected, not folded in. */
    public static final int MAX_WORLDS = 64;

    /** One task longer than this is outside a single task's plausible span and is rejected as a
     * discontinuity instead of entering a bucket. */
    public static final long MAX_SINGLE_REPORT_NANOS = 60_000_000_000L;

    /** One world's time samples in the current window. */
    public record Sample(String worldId, long epoch, long genTaskNanos, long lightTaskNanos,
                         long reports) {
    }

    /** One drained window. Rejection counters are cumulative and are copied so a reader can tell
     * why a report did not enter a bucket. */
    public record WindowReading(long tickIndex, Map<String, Long> epochs,
                                Map<String, Sample> samples, long droppedWindows,
                                long rejectedNegative, long rejectedNonTarget,
                                long rejectedUnplaced, long rejectedWrongWorld,
                                long rejectedDiscontinuity, long rejectedCapacity,
                                long rejectedClosed) {
    }

    private static final class Bucket {

        private final String worldId;
        private final long epoch;
        private long genTaskNanos;
        private long lightTaskNanos;
        private long reports;

        private Bucket(String worldId, long epoch) {
            this.worldId = worldId;
            this.epoch = epoch;
        }
    }

    private static final class Window {

        private final long tickIndex;
        private final Map<String, Long> epochs;
        private final Map<String, Bucket> buckets = new LinkedHashMap<>();

        private Window(long tickIndex, Map<String, Long> epochs) {
            this.tickIndex = tickIndex;
            this.epochs = new LinkedHashMap<>(epochs);
        }
    }

    private Window window;
    private long droppedWindows;
    private long rejectedNegative;
    private long rejectedNonTarget;
    private long rejectedUnplaced;
    private long rejectedWrongWorld;
    private long rejectedDiscontinuity;
    private long rejectedCapacity;
    private long rejectedClosed;

    /** Opens a fresh window for one owner tick and the epoch of each live world. A window the owner
     * never drained is dropped, never merged into the new one; the drop is counted. */
    public synchronized void openWindow(long tickIndex, Map<String, Long> epochs) {
        if (window != null) {
            droppedWindows++;
        }
        window = new Window(tickIndex, epochs == null ? Map.of() : epochs);
    }

    /** The tick of the open window, or -1 when no window is open. */
    public synchronized long windowTick() {
        return window == null ? -1L : window.tickIndex;
    }

    /** Closes the window; reports that arrive afterwards are rejected as closed. */
    public synchronized void close() {
        window = null;
    }

    /** Removes one world from the open window: its samples are dropped and a later report of it is
     * rejected as a world outside the window, so an unload leaves no time behind. */
    public synchronized void forgetWorld(String worldId) {
        if (window != null && worldId != null) {
            window.buckets.remove(worldId);
            window.epochs.remove(worldId);
        }
    }

    /** Reports one executed mailbox task. Only the generation and light names enter a bucket, and
     * only while a window is open and the mailbox is placed on a world of that window. */
    public synchronized boolean report(Object mailbox, String mailboxName, long nanos) {
        if (window == null) {
            rejectedClosed++;
            return false;
        }
        if (nanos < 0L) {
            rejectedNegative++;
            return false;
        }
        if (nanos > MAX_SINGLE_REPORT_NANOS) {
            rejectedDiscontinuity++;
            return false;
        }
        if (!WORLDGEN.equals(mailboxName) && !LIGHT.equals(mailboxName)) {
            rejectedNonTarget++;
            return false;
        }
        if (mailbox == null || PrtsPipelineRows.isNonTargetMailbox(mailbox)) {
            rejectedNonTarget++;
            return false;
        }
        String worldId = PrtsPipelineRows.worldOf(mailbox);
        if (worldId == null || PrtsPipelineRows.UNPLACED_WORLD.equals(worldId)) {
            rejectedUnplaced++;
            return false;
        }
        Long epoch = window.epochs.get(worldId);
        if (epoch == null) {
            rejectedWrongWorld++;
            return false;
        }
        Bucket bucket = window.buckets.get(worldId);
        if (bucket == null) {
            if (window.buckets.size() >= MAX_WORLDS) {
                rejectedCapacity++;
                return false;
            }
            bucket = new Bucket(worldId, epoch);
            window.buckets.put(worldId, bucket);
        }
        if (WORLDGEN.equals(mailboxName)) {
            bucket.genTaskNanos = saturatedAdd(bucket.genTaskNanos, nanos);
        } else {
            bucket.lightTaskNanos = saturatedAdd(bucket.lightTaskNanos, nanos);
        }
        bucket.reports++;
        return true;
    }

    /** Takes the open window and leaves none; a second drain before the next open answers an empty
     * window, so a report cannot be consumed twice. */
    public synchronized WindowReading drain() {
        Window drained = window;
        window = null;
        Map<String, Sample> samples = new LinkedHashMap<>();
        if (drained != null) {
            for (Bucket bucket : drained.buckets.values()) {
                samples.put(bucket.worldId, new Sample(bucket.worldId, bucket.epoch,
                    bucket.genTaskNanos, bucket.lightTaskNanos, bucket.reports));
            }
        }
        return new WindowReading(drained == null ? -1L : drained.tickIndex,
            drained == null ? Map.of() : Map.copyOf(drained.epochs), Map.copyOf(samples),
            droppedWindows, rejectedNegative, rejectedNonTarget, rejectedUnplaced,
            rejectedWrongWorld, rejectedDiscontinuity, rejectedCapacity, rejectedClosed);
    }

    public synchronized void reset() {
        window = null;
        droppedWindows = 0L;
        rejectedNegative = 0L;
        rejectedNonTarget = 0L;
        rejectedUnplaced = 0L;
        rejectedWrongWorld = 0L;
        rejectedDiscontinuity = 0L;
        rejectedCapacity = 0L;
        rejectedClosed = 0L;
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
