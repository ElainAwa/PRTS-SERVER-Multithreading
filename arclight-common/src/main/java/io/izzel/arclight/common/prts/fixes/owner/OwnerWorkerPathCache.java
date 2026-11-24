/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.owner;

import net.minecraft.world.level.pathfinder.PathTypeCache;

/** One path type cache per worker: a body is the only user of its thread's cache and clears it before
 * the next body, so thousands of searches never allocate their own 4096-entry arrays. */
public final class OwnerWorkerPathCache {

    private static final ThreadLocal<PathTypeCache> CACHE = ThreadLocal.withInitial(PathTypeCache::new);

    private OwnerWorkerPathCache() {
    }

    public static PathTypeCache borrow() {
        // No clearing is needed: an entry is keyed by its packed block position, so a stale entry can
        // only answer a block the search actually asks for again. The arrays are fixed size and the
        // newest computation simply overwrites its slot.
        return CACHE.get();
    }
}
