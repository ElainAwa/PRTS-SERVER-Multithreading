/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

/**
 * The seam a world write path hands a write to whoever watches write rights. The fast question costs
 * one volatile read while no watcher is installed, and the world identity and the deferred write are
 * built only on the slow path. The seam carries no kernel type on purpose.
 */
public final class PrtsWorldWriteTaps {

    public interface DeferredWrite {

        boolean apply();
    }

    public interface BlockWriteTap {

        int PASS = 0;

        int JUDGE = 1;

        /**
         * Answers whether a block write may pass without the slow path.
         * @return {@link #PASS} or {@link #JUDGE}
         */
        int classifyBlockWrite(Object levelRef);

        /**
         * Judges a block write on the slow path.
         * @return true when the write may proceed now; otherwise it is applied later through the deferred write
         */
        boolean admitBlockWrite(Object levelRef, String worldId, DeferredWrite deferred);
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

    /**
     * Opens one decision against the watcher that answers the fast question. The watcher is read once
     * here and the handle carries it, so a reload between the fast question and the slow path cannot
     * change which watcher judges the write.
     */
    public static Decision beginBlockWrite(Object levelRef) {
        BlockWriteTap tap = blockWriteTap;
        return new Decision(tap, tap == null ? BlockWriteTap.PASS : tap.classifyBlockWrite(levelRef));
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
    }
}
