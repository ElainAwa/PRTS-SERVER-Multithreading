/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.interaction;

import io.izzel.arclight.common.prts.kernel.decision.ExceptionPolicy;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
import io.izzel.arclight.common.prts.kernel.domain.entity.program.PoiAdmission;
import net.minecraft.core.BlockPos;

/** The POI single-winner wiring: the server tick thread binds the current typed update, the
 * PoiManager entry reads it and decides one winner per POI record. Without a bound context (property
 * off, worker, or a later tick) every entry falls back to the host path. */
public final class PoiSingleWinnerWiring {

    /** The one claim store of this process; only the server tick thread touches it in production. */
    private static PoiClaimStore store = new PoiClaimStore();
    private static final ExceptionPolicy POLICY = ExceptionPolicy.defaults();

    private PoiSingleWinnerWiring() {
    }

    public static boolean enabled() {
        return EntityTickOwnership.poiEnabled();
    }

    static String recordKey(EntityTickOwnership.TypedUpdate update, BlockPos pos) {
        return "world=" + update.worldId() + "|incarnation=" + update.worldIncarnation()
            + "|poi=" + pos.asLong();
    }

    static String holder(EntityTickOwnership.TypedUpdate update) {
        return update.unitId() + "#" + update.ordinal();
    }

    /** Test-only reset of the singleton claim store; the store is owned by the server tick thread. */
    public static void resetForTests() {
        store = new PoiClaimStore();
    }

    /** Whether the take must be refused before the host effect. */
    public static boolean denyTake(BlockPos pos) {
        EntityTickOwnership.TypedUpdate update = EntityTickOwnership.poiContext();
        if (!enabled() || update == null || !EntityTickOwnership.hasPoiScope()) {
            return EntityTickOwnership.ownerCallWithoutScope();
        }
        return !PoiAdmission.admitOwned(store, PoiAdmission.Operation.TAKE,
            recordKey(update, pos), holder(update), POLICY).singleWinner();
    }

    /** Whether the release must be refused before the host effect. */
    public static boolean denyRelease(BlockPos pos) {
        EntityTickOwnership.TypedUpdate update = EntityTickOwnership.poiContext();
        if (!enabled() || update == null || !EntityTickOwnership.hasPoiScope()) {
            return EntityTickOwnership.ownerCallWithoutScope();
        }
        return !PoiAdmission.admitOwned(store, PoiAdmission.Operation.RELEASE,
            recordKey(update, pos), holder(update), POLICY).singleWinner();
    }
}
