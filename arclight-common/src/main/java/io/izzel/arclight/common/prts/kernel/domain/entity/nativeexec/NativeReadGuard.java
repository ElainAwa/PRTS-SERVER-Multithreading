/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import java.util.EnumSet;
import java.util.Set;

/** The thread-local fence of one read node: a row write, plugin event, host callback, shared draw or
 * chunk demand records a refusal here and throws instead of waiting for a host that is not pumping.
 * A refusal stays recorded even when the node catches the throw. */
public final class NativeReadGuard {

    /** The accesses a read node must never perform; each is recorded by its own entry point. */
    public enum Refusal {
        WRITE,
        EVENT,
        HOST_CALLBACK,
        DRAW,
        CHUNK_DEMAND
    }

    /** One bound read node: the row it belongs to and the accesses it attempted. */
    public static final class Frame {

        private final String rowKey;
        private final Frame previous;
        private final EnumSet<Refusal> refusals = EnumSet.noneOf(Refusal.class);

        private Frame(String rowKey, Frame previous) {
            this.rowKey = rowKey;
            this.previous = previous;
        }

        public String rowKey() {
            return rowKey;
        }

        public Frame previous() {
            return previous;
        }

        public boolean refused() {
            return !refusals.isEmpty();
        }

        public Set<Refusal> refusals() {
            return Set.copyOf(refusals);
        }

        /** The first refused access, in the order the node attempted them; null when none. */
        public Refusal first() {
            return refusals.isEmpty() ? null : refusals.iterator().next();
        }

        private void note(Refusal refusal) {
            refusals.add(refusal);
        }
    }

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private NativeReadGuard() {
    }

    /** Binds the calling thread as one read node; the wave binds one frame per node and never nests. */
    public static void enter(String rowKey) {
        CURRENT.set(new Frame(rowKey == null ? "" : rowKey, CURRENT.get()));
    }

    public static void exit() {
        Frame frame = CURRENT.get();
        if (frame == null) {
            return;
        }
        if (frame.previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(frame.previous);
        }
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static Frame current() {
        return CURRENT.get();
    }

    /** The first refused access of the bound frame, or null outside a node and after a clean one. */
    public static Refusal refusal() {
        Frame frame = CURRENT.get();
        return frame == null ? null : frame.first();
    }

    /** Records one attempted access and refuses it. Outside a read node this is a no-op, so the
     * production entry points that call it keep their original path. */
    public static void fence(Refusal refusal) {
        Frame frame = CURRENT.get();
        if (frame == null) {
            return;
        }
        frame.note(refusal);
        throw new IllegalStateException(
            "a read-wave node may not " + refusal + " for row " + frame.rowKey());
    }
}
