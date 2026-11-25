/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.interaction;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * The point-of-interest single-winner bridge. It observes the manager's own take and release
 * results: a take that returned a record is recorded under that returned record, and a release
 * that really happened hands that same record back. The search centre passed to the take is never
 * used as the claim key, so a refused or admitted claim can no longer name a record the world did
 * not use. Ordering and refusal of a competing claim are not implemented here yet; the bridge is
 * off in every shipped build and is not a single-winner authority until the owner side runs the
 * operation on the authoritative thread and lets the real result drive the caller.
 */
public final class PoiSingleWinnerWiring {

    private static PoiClaimStore store = new PoiClaimStore();

    private PoiSingleWinnerWiring() {
    }

    public static boolean enabled() {
        return EntityTickOwnership.poiEnabled();
    }

    static String recordKey(EntityTickOwnership.TypedUpdate update, BlockPos record) {
        return "world=" + update.worldId() + "|incarnation=" + update.worldIncarnation()
            + "|poi=" + record.asLong();
    }

    static String holder(EntityTickOwnership.TypedUpdate update) {
        return update.unitId() + "#" + update.ordinal();
    }

    /** Test-only reset of the singleton claim store; the store is owned by the server tick thread. */
    public static void resetForTests() {
        store = new PoiClaimStore();
    }

    /** Test-only view of the claim store; no production caller. */
    public static PoiClaimStore storeForTests() {
        return store;
    }

    /** Records a take the manager actually performed, under the record it returned. A failed take
     * (empty result) records nothing; a worker without a bound owner context records nothing. */
    public static void noteTakeResult(BlockPos searchCentre, Optional<BlockPos> record) {
        if (!enabled() || record == null || record.isEmpty()) {
            return;
        }
        EntityTickOwnership.TypedUpdate update = EntityTickOwnership.poiContext();
        if (update == null) {
            return;
        }
        store.claim(recordKey(update, record.get()), holder(update));
    }

    /** Records a release the manager actually performed. A refused release records nothing; the
     * record key comes from the released position, which is the record the manager named. */
    public static void noteReleaseResult(BlockPos record, boolean released) {
        if (!enabled() || !released) {
            return;
        }
        EntityTickOwnership.TypedUpdate update = EntityTickOwnership.poiContext();
        if (update == null) {
            return;
        }
        store.release(recordKey(update, record), holder(update));
    }
}
