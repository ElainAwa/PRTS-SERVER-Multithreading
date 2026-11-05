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
    }

    private static volatile BlockWriteTap blockWriteTap;

    private PrtsWorldWriteTaps() {
    }

    public static void install(BlockWriteTap tap) {
        blockWriteTap = tap;
    }

    public static boolean installed() {
        return blockWriteTap != null;
    }

    /** Opens one decision against the watcher read here; the handle carries it, so a reload between
     * the fast question and the slow path cannot change which watcher judges the write. */
    public static Decision beginBlockWrite(Object levelRef) {
        BlockWriteTap tap = blockWriteTap;
        return new Decision(tap, tap == null ? BlockWriteTap.PASS : tap.classifyBlockWrite(levelRef));
    }

    /** Opens one decision for a write made at a known block position: a watcher that cannot use the
     * three coordinates answers as it does for a write that has none. */
    public static Decision beginBlockWrite(Object levelRef, int x, int y, int z) {
        BlockWriteTap tap = blockWriteTap;
        return new Decision(tap, tap == null ? BlockWriteTap.PASS : tap.classifyBlockWrite(levelRef, x, y, z));
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
