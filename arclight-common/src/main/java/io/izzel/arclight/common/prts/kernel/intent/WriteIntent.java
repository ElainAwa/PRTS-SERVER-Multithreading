/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

/** Frozen by the channel under its lock: the order is assigned on acceptance, so orders in one
 * world shard are contiguous and a refused draft consumes none. */
public record WriteIntent(long intentId, String srcWorldId, String dstWorldId, String dstDomainId,
                          long expectedVersion, long worldEpoch, long frozenOrder,
                          String payloadHandle, String waitPoint, String siteId) {

    public static final long UNFROZEN = -1L;

    public static final long UNTRACKED_EPOCH = 0L;

    public static WriteIntent draft(long intentId, String srcWorldId, String dstWorldId,
                                    String dstDomainId, long expectedVersion, long worldEpoch,
                                    String payloadHandle, String waitPoint, String siteId) {
        return new WriteIntent(intentId, srcWorldId, dstWorldId, dstDomainId, expectedVersion,
            worldEpoch, UNFROZEN, payloadHandle, waitPoint, siteId);
    }

    public WriteIntent withFrozenOrder(long order) {
        return new WriteIntent(intentId, srcWorldId, dstWorldId, dstDomainId, expectedVersion,
            worldEpoch, order, payloadHandle, waitPoint, siteId);
    }
}
