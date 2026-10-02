/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

/**
 * The seam a world write path uses to hand a write to whoever watches write rights.
 *
 * <p>A write path that is already hooked asks the fast question first and pays one volatile read when no
 * watcher is installed. Only when the fast question asks for the slow path does the caller name the world
 * and build the deferred write, so the common case allocates nothing at all.</p>
 *
 * <p>The seam carries no kernel type on purpose: it sits below both the write paths and the
 * watcher, so neither side has to depend on the other to be compiled.</p>
 */
public final class PrtsWorldWriteTaps {

    /** A write the caller hands over so it is applied later instead of now. */
    public interface DeferredWrite {

        /** @return {@code true} when the write landed */
        boolean apply();
    }

    /** The watcher behind the seam. */
    public interface BlockWriteTap {

        /** Answer of a fast question that let the write pass without any further work. */
        int PASS = 0;

        /** Answer of a fast question that asks the caller to run the slow path. */
        int JUDGE = 1;

        /**
         * Answers whether a block write may pass without the slow path.
         *
         * @param levelRef the level the write targets, as an opaque reference
         * @return {@link #PASS} or {@link #JUDGE}
         */
        int classifyBlockWrite(Object levelRef);

        /**
         * Judges a block write on the slow path.
         *
         * @param levelRef the level the write targets, as an opaque reference
         * @param worldId  readable identity of that level
         * @param deferred the write itself, applied later when it is handed over
         * @return {@code true} when the write may proceed now
         */
        boolean admitBlockWrite(Object levelRef, String worldId, DeferredWrite deferred);
    }

    private static volatile BlockWriteTap blockWriteTap;

    private PrtsWorldWriteTaps() {
    }

    /**
     * Installs the watcher.
     *
     * @param tap the watcher, or {@code null} to remove it
     */
    public static void install(BlockWriteTap tap) {
        blockWriteTap = tap;
    }

    /** @return whether a watcher is installed */
    public static boolean installed() {
        return blockWriteTap != null;
    }

    /**
     * Opens one decision against the watcher that answers the fast question.
     *
     * <p>The watcher is read once, here, and the handle that comes back carries it. A configuration
     * reload that removes or replaces the watcher between the fast question and the slow path
     * therefore cannot change which watcher judges the write: the handle decides, either with the
     * watcher that admitted the attempt or with no watcher at all, and never with a third one that
     * happened to be installed in between.</p>
     *
     * @param levelRef the level the write targets
     * @return the handle of this decision
     */
    public static Decision beginBlockWrite(Object levelRef) {
        BlockWriteTap tap = blockWriteTap;
        return new Decision(tap, tap == null ? BlockWriteTap.PASS : tap.classifyBlockWrite(levelRef));
    }

    /** One write decision, bound to the watcher that answered its fast question. */
    public static final class Decision {

        private final BlockWriteTap tap;
        private final int verdict;

        private Decision(BlockWriteTap watcher, int verdict) {
            this.tap = watcher;
            this.verdict = verdict;
        }

        /** @return whether the caller has to run the slow path */
        public boolean judge() {
            return verdict != BlockWriteTap.PASS;
        }

        /**
         * Judges the write on the slow path.
         *
         * @param levelRef the level the write targets
         * @param worldId  readable identity of that level
         * @param deferred the write itself, applied later when it is handed over
         * @return {@code true} when the write may proceed now
         */
        public boolean admit(Object levelRef, String worldId, DeferredWrite deferred) {
            return tap == null || tap.admitBlockWrite(levelRef, worldId, deferred);
        }
    }
}
