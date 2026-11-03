/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import java.util.Optional;

/** The synchronous event dispatch face of the platform bus. The probe observes the posts rooted at a
 * server tick or a level unload, compares the real listener invocations against the frozen dispatch
 * list the bus handed to that post, and keeps the deepest same-thread same-bus nesting. It is off
 * unless the platform bridge installs it and the property is set; while off no frame is kept and no
 * reading is produced. A failed, truncated or expectation-less dispatch invalidates the whole
 * generation instead of publishing a zero. */
public final class EventOrderProbe {

    /** The property that arms the probe once the platform bridge has installed it. */
    public static final String PROPERTY = "arclight.prts.eventOrderProbe";

    /** The largest frozen dispatch list one post may carry before the trace is called truncated. */
    static final int MAX_LISTENERS = 512;

    /** The deepest same-thread same-bus nesting the trace keeps before it is called truncated. */
    static final int MAX_DEPTH = 64;

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private static final Object LOCK = new Object();

    private static volatile boolean installed;
    private static volatile long generation;
    private static volatile long violations;
    private static volatile int maxDepth;
    private static volatile boolean covered;
    private static volatile boolean invalid;

    private EventOrderProbe() {
    }

    /** Whether the bridge is installed and the property is set; a process that declared nothing
     * keeps every dispatch at one boolean read. */
    public static boolean enabled() {
        return installed && Boolean.getBoolean(PROPERTY);
    }

    /** Installs the bridge face and starts a new generation. */
    public static void install() {
        synchronized (LOCK) {
            installed = true;
            clearLocked();
            generation++;
        }
    }

    /** Removes the bridge face and drops every reading of the old generation. */
    public static void uninstall() {
        synchronized (LOCK) {
            installed = false;
            clearLocked();
            generation++;
        }
    }

    /** Starts a new generation: every counter, the coverage claim and the invalid state restart. */
    public static void reset() {
        synchronized (LOCK) {
            clearLocked();
            generation++;
        }
    }

    /** Opens one dispatch frame and answers its token, or null when the probe is off. The caller must
     * pair it with {@link #end(Object, boolean)} on the same thread; a frame that is not a root and
     * has no observed ancestor is kept only so nested posts cannot join the wrong subtree. */
    public static Object begin(Object bus, Object[] expected, boolean rootEvent) {
        if (!enabled()) {
            return null;
        }
        Frame parent = CURRENT.get();
        boolean observed;
        int depth;
        if (parent == null) {
            observed = rootEvent;
            depth = observed ? 1 : 0;
        } else if (parent.observed) {
            observed = parent.bus == bus;
            depth = observed ? parent.depth + 1 : 0;
        } else {
            observed = rootEvent;
            depth = observed ? 1 : 0;
        }
        long frameGeneration = generation;
        Frame frame = new Frame(bus, expected, depth, parent, observed, frameGeneration);
        if (observed) {
            if (expected == null || expected.length > MAX_LISTENERS || depth > MAX_DEPTH) {
                markInvalid(frameGeneration);
            }
        }
        CURRENT.set(frame);
        return frame;
    }

    /** Records one real listener invocation of the newest frame on this thread. */
    public static void invokedCurrent(Object listener) {
        Frame frame = CURRENT.get();
        if (frame == null || !frame.observed || frame.expected == null) {
            return;
        }
        int index = frame.next++;
        if (index >= frame.expected.length || frame.expected[index] != listener) {
            frame.mismatch = true;
        }
    }

    /** Closes the newest frame on this thread; a failed dispatch invalidates the generation. */
    public static void end(Object token, boolean failed) {
        if (!(token instanceof Frame frame)) {
            return;
        }
        if (frame.parent == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(frame.parent);
        }
        if (!frame.observed) {
            return;
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
            if (frame.depth > maxDepth) {
                maxDepth = frame.depth;
            }
            if (frame.depth == 1) {
                covered = true;
            }
        }
    }

    /** The reading of the current generation, or empty while the source was not covered or an
     * observed dispatch failed, was truncated or carried no expectation. */
    public static Optional<Reading> reading() {
        synchronized (LOCK) {
            if (!enabled() || !covered || invalid) {
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

    /** One published reading: the number of mismatched dispatch frames and the deepest synchronous
     * same-thread same-bus nesting seen in the generation; the root post counts as one. */
    public record Reading(long orderViolation, int nestingDepth, long generation) {
    }

    private static final class Frame {

        private final Object bus;
        private final Object[] expected;
        private final int depth;
        private final Frame parent;
        private final boolean observed;
        private final long generation;
        private int next;
        private boolean mismatch;

        private Frame(Object bus, Object[] expected, int depth, Frame parent, boolean observed,
                      long generation) {
            this.bus = bus;
            this.expected = expected;
            this.depth = depth;
            this.parent = parent;
            this.observed = observed;
            this.generation = generation;
        }
    }
}
