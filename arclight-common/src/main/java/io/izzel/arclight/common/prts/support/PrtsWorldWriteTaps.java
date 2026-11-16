/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import java.util.function.Supplier;

/** The seam a world write path hands a write to whoever watches write rights; no kernel type crosses it. */
public final class PrtsWorldWriteTaps {

    public interface DeferredWrite {

        boolean apply();
    }

    public interface BlockWriteTap {

        int PASS = 0;

        int JUDGE = 1;

        /** @return {@link #PASS} or {@link #JUDGE} */
        int classifyBlockWrite(Object levelRef);

        /** @return {@link #PASS} or {@link #JUDGE} for a write whose block position the caller can name */
        int classifyBlockWrite(Object levelRef, int x, int y, int z);

        /** @return true when the write may proceed now; false means it is applied later through the deferred write */
        boolean admitBlockWrite(Object levelRef, String worldId, DeferredWrite deferred);

        /** The lazy form: the deferred write is materialized only when a routed write really needs
         * it, so an ordinary granted write pays no clone/allocation. */
        default boolean admitBlockWriteLazy(Object levelRef, String worldId,
                                            Supplier<DeferredWrite> deferred) {
            return admitBlockWrite(levelRef, worldId,
                deferred == null ? null : deferred.get());
        }

        /** Reports what the write the judged path let through actually did, with the state read
         * back after the call. A write that never reached the world (a capture) and a failed call
         * both report {@code applied=false}: admission is not a landing. */
        default void completeBlockWrite(Object levelRef, int x, int y, int z, Object stateAfter,
                                        boolean applied) {
        }
    }

    private static volatile BlockWriteTap blockWriteTap;
    /** The level whose judged write is still waiting for its actual result on this thread. */
    private static final ThreadLocal<Object> judgedLevel = new ThreadLocal<>();

    private PrtsWorldWriteTaps() {
    }

    public static void install(BlockWriteTap tap) {
        blockWriteTap = tap;
    }

    public static boolean installed() {
        return blockWriteTap != null;
    }

    /** Marks that the write this thread is about to make was judged and owes a result report. */
    public static void noteJudgedWrite(Object levelRef) {
        judgedLevel.set(levelRef);
    }

    /** The level whose judged write still owes a result on this thread, or null. */
    public static Object judgedWrite() {
        return judgedLevel.get();
    }

    public static void clearJudgedWrite() {
        judgedLevel.remove();
    }

    /** Reports the actual result of one judged write to the watcher that admitted it. */
    public static void completeBlockWrite(Object levelRef, int x, int y, int z, Object stateAfter,
                                          boolean applied) {
        BlockWriteTap tap = blockWriteTap;
        if (tap != null) {
            tap.completeBlockWrite(levelRef, x, y, z, stateAfter, applied);
        }
    }

    /** Opens one decision against the watcher read here; the handle carries it, so a reload between
     * the fast question and the slow path cannot change which watcher judges the write. */
    public static Decision beginBlockWrite(Object levelRef) {
        BlockWriteTap tap = blockWriteTap;
        int verdict = tap == null ? BlockWriteTap.PASS : tap.classifyBlockWrite(levelRef);
        noteJudged(tap, levelRef, verdict);
        return new Decision(tap, verdict);
    }

    /** Opens one decision for a write made at a known block position: a watcher that cannot use the
     * three coordinates answers as it does for a write that has none. */
    public static Decision beginBlockWrite(Object levelRef, int x, int y, int z) {
        BlockWriteTap tap = blockWriteTap;
        int verdict = tap == null ? BlockWriteTap.PASS : tap.classifyBlockWrite(levelRef, x, y, z);
        noteJudged(tap, levelRef, verdict);
        return new Decision(tap, verdict);
    }

    private static void noteJudged(BlockWriteTap tap, Object levelRef, int verdict) {
        if (tap != null && verdict != BlockWriteTap.PASS) {
            // The caller starts the real write after this answer: the result is reported back to the
            // watcher when the call returns, not when the admission is handed out.
            noteJudgedWrite(levelRef);
        } else {
            clearJudgedWrite();
        }
    }

    public static final class Decision {

        private final BlockWriteTap tap;
        private final int verdict;

        private Decision(BlockWriteTap watcher, int verdict) {
            this.tap = watcher;
            this.verdict = verdict;
        }

        public boolean judge() {
            return verdict != BlockWriteTap.PASS;
        }

        public boolean admit(Object levelRef, String worldId, DeferredWrite deferred) {
            return tap == null || tap.admitBlockWrite(levelRef, worldId, deferred);
        }

        public boolean admitLazy(Object levelRef, String worldId, Supplier<DeferredWrite> deferred) {
            return tap == null || tap.admitBlockWriteLazy(levelRef, worldId, deferred);
        }
    }
}
