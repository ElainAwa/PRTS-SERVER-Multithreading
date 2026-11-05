/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import java.util.Optional;

/** Read-only Bukkit dispatch observation for the RCON root event and its same-thread synchronous
 * children. A frame compares every real RegisteredListener invocation against the HandlerList
 * snapshot the same fireEvent will iterate; skipped listeners are a legal subsequence, reordering
 * or an unknown listener is a violation. It retains no Event, Listener or Plugin object in a
 * published reading. */
public final class BukkitEventOrderProbe {

    static final int MAX_LISTENERS = 512;
    static final int MAX_DEPTH = 64;

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static final Object LOCK = new Object();

    private static volatile boolean installed;
    private static volatile long generation;
    private static volatile long violations;
    private static volatile int maxDepth;
    private static volatile boolean covered;
    private static volatile boolean invalid;

    private BukkitEventOrderProbe() {
    }

    /** Installs the probe; it is inert until the kernel installs it. */
    public static void install() {
        synchronized (LOCK) {
            installed = true;
            clearLocked();
            generation++;
        }
    }

    /** Removes the probe and drops the old generation. */
    public static void uninstall() {
        synchronized (LOCK) {
            installed = false;
            clearLocked();
            generation++;
        }
    }

    /** Starts a new generation and clears all counters. */
    public static void reset() {
        synchronized (LOCK) {
            clearLocked();
            generation++;
        }
    }

    /** Opens a frame for one fireEvent call. A non-root call outside an observed root creates no
     * frame. The expected array is the real HandlerList snapshot taken at the same call. */
    public static void begin(Object[] expected, boolean root) {
        if (!installed) {
            return;
        }
        Frame parent = CURRENT.get();
        if (parent == null && !root) {
            return;
        }
        int depth = parent == null ? 1 : parent.depth + 1;
        long frameGeneration = generation;
        Frame frame = new Frame(expected, depth, parent, frameGeneration);
        if (expected == null || expected.length > MAX_LISTENERS || depth > MAX_DEPTH) {
            markInvalid(frameGeneration);
        }
        CURRENT.set(frame);
    }

    /** Records one real RegisteredListener.callEvent, in the actual invocation order. */
    public static void invokedCurrent(Object listener) {
        Frame frame = CURRENT.get();
        if (frame == null || frame.expected == null || frame.mismatch) {
            return;
        }
        int index = frame.next;
        while (index < frame.expected.length && frame.expected[index] != listener) {
            index++;
        }
        if (index >= frame.expected.length) {
            frame.mismatch = true;
        } else {
            frame.next = index + 1;
        }
    }

    /** Closes the newest frame; a failed dispatch invalidates the generation. */
    public static void end(boolean failed) {
        Frame frame = CURRENT.get();
        if (frame == null) {
            return;
        }
        if (frame.parent == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(frame.parent);
        }
        synchronized (LOCK) {
            if (frame.generation != generation) {
                return;
            }
            if (failed) {
                invalid = true;
            }
            if (frame.mismatch) {
                violations++;
            }
            maxDepth = Math.max(maxDepth, frame.depth);
            if (frame.depth == 1) {
                covered = true;
            }
        }
    }

    /** The current generation reading, empty while no RCON root was covered or the generation is
     * invalid. */
    public static Optional<Reading> reading() {
        synchronized (LOCK) {
            if (!installed || !covered || invalid) {
                return Optional.empty();
            }
            return Optional.of(new Reading(violations, maxDepth, generation));
        }
    }

    private static void markInvalid(long frameGeneration) {
        synchronized (LOCK) {
            if (frameGeneration == generation) {
                invalid = true;
            }
        }
    }

    private static void clearLocked() {
        violations = 0L;
        maxDepth = 0;
        covered = false;
        invalid = false;
    }

    /** One published reading; depth counts the root RemoteServerCommandEvent as one. */
    public record Reading(long orderViolation, int nestingDepth, long generation) {
    }

    private static final class Frame {
        private final Object[] expected;
        private final int depth;
        private final Frame parent;
        private final long generation;
        private int next;
        private boolean mismatch;

        private Frame(Object[] expected, int depth, Frame parent, long generation) {
            this.expected = expected;
            this.depth = depth;
            this.parent = parent;
            this.generation = generation;
        }
    }
}
