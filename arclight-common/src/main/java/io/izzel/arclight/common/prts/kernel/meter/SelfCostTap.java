/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.meter;

import io.izzel.arclight.common.prts.kernel.observe.BlockEntityAttribution;
import io.izzel.arclight.common.prts.kernel.observe.QuotaCostReadings;
import io.izzel.arclight.common.prts.support.PrtsSelfCosts;

public final class SelfCostTap implements PrtsSelfCosts.CostTap {

    private static final String BLOCK_ENTITIES = "block-entities";

    @Override
    public void entityRow(String worldId, String rowRef, long nanos) {
        SelfTimers.note(SelfClass.ENTITY, worldId, rowRef, nanos);
        QuotaCostReadings.note(QuotaCostReadings.QuotaClass.ENTITY, nanos);
    }

    @Override
    public void blockEntities(String worldId, long nanos) {
        SelfTimers.note(SelfClass.BLOCKENTITY, worldId, BLOCK_ENTITIES, nanos);
        QuotaCostReadings.note(QuotaCostReadings.QuotaClass.DEVICE, nanos);
        BlockEntityAttribution.noteSegment(worldId, nanos);
    }
}
