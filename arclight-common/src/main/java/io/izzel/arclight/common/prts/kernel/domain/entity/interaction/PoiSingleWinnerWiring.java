/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.interaction;

import io.izzel.arclight.common.prts.kernel.decision.ExceptionPolicy;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
import io.izzel.arclight.common.prts.kernel.domain.entity.program.PoiAdmission;
import net.minecraft.core.BlockPos;

/** The point-of-interest single-winner bridge. Known limitation: a claim is recorded under the
 * search-centre coordinate handed to PoiManager.take, while the manager selects the actual record
 * inside that call; the release path then names the selected record, so the held key and the
 * released key differ and the bridge can refuse or admit a record the world never uses. It is off
 * in every shipped build; do not enable it until the owner side runs the real operation on the
 * authoritative thread and returns the manager's own result to the caller. */
public final class PoiSingleWinnerWiring {

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
        if (!enabled()) {
            return false;
        }
        EntityTickOwnership.TypedUpdate update = EntityTickOwnership.poiContext();
        if (update == null) {
            // An admitted owner that lost its scope must not fall through to the old POI write; an
            // un-taken host call keeps the original path.
            return EntityTickOwnership.poiCallRefused();
        }
        return !PoiAdmission.admitOwned(store, PoiAdmission.Operation.TAKE,
            recordKey(update, pos), holder(update), POLICY).singleWinner();
    }

    /** Whether the release must be refused before the host effect. */
    public static boolean denyRelease(BlockPos pos) {
        if (!enabled()) {
            return false;
        }
        EntityTickOwnership.TypedUpdate update = EntityTickOwnership.poiContext();
        if (update == null) {
            return EntityTickOwnership.poiCallRefused();
        }
        return !PoiAdmission.admitOwned(store, PoiAdmission.Operation.RELEASE,
            recordKey(update, pos), holder(update), POLICY).singleWinner();
    }
}