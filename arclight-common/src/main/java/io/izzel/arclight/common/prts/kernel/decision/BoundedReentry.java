/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The bounded same-stack reentry of one decision node: the parent reserves the upper bound of the
 * child effects before the first child runs, every child effect has its own identity, and one child
 * over the reservation faults the node while keeping the prefix already applied. The scope is
 * owned by the thread that opened it; no child is ever dispatched after the scope closed.
 */
public final class BoundedReentry {

    /** One child effect crossed the parent's reservation. */
    public static final class OverBound extends RuntimeException {

        public OverBound(String childId) {
            super("the reserved reentry bound was crossed by " + childId);
        }
    }

    /** The parent's declared upper bound: the most child effects one decision may run. */
    public static BoundedReentry reserve(int bound) {
        return new BoundedReentry(bound);
    }

    private final int bound;
    private final Thread owner = Thread.currentThread();

    private BoundedReentry(int bound) {
        if (bound < 0) {
            throw new IllegalArgumentException("a reentry bound cannot be negative");
        }
        this.bound = bound;
    }

    /** Opens the one scope of this decision; a second open while one is active is refused. */
    public Scope open() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("a reentry scope belongs to the thread that reserved it");
        }
        return new Scope(bound);
    }

    public int bound() {
        return bound;
    }

    /** One open scope: every child effect is entered and left once, and each needs its own handle. */
    public static final class Scope {

        private final int bound;
        private final Set<String> children = new HashSet<>();
        private int entered;
        private boolean closed;

        private Scope(int bound) {
            this.bound = bound;
        }

        /** Enters one child effect under its own identity, or faults when the bound is crossed. */
        public void enter(String childId) {
            if (closed) {
                throw new IllegalStateException("the reentry scope is closed");
            }
            Objects.requireNonNull(childId, "childId");
            if (childId.isEmpty()) {
                throw new IllegalArgumentException("a child effect needs an identity");
            }
            if (!children.add(childId)) {
                throw new IllegalArgumentException("a child effect identity is entered once: " + childId);
            }
            if (entered >= bound) {
                children.remove(childId);
                throw new OverBound(childId);
            }
            entered++;
        }

        /** Leaves one child effect; the identity cannot be used again. */
        public void leave(String childId) {
            if (childId == null || !children.remove(childId)) {
                throw new IllegalArgumentException("no open child effect: " + childId);
            }
        }

        /** The child effects entered so far; the prefix a fault keeps. */
        public int entered() {
            return entered;
        }

        public int bound() {
            return bound;
        }

        /** Closes the scope; a later enter is refused instead of dispatching a child. */
        public void close() {
            closed = true;
            children.clear();
        }

        public boolean closed() {
            return closed;
        }
    }
}
