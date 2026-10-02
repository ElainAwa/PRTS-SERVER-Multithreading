/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

/** A caller builds a draft and the channel freezes it: the order is assigned when the channel
 * accepts the intent, under the same lock, so the orders inside one world's shard are contiguous
 * and a draft refused at the depth limit consumes none of them. */
public record WriteIntent(long intentId, String srcWorldId, String dstWorldId, String dstDomainId,
                          long expectedVersion, long worldEpoch, long frozenOrder,
                          String payloadHandle, String waitPoint, String siteId) {

    /** Order of a draft the channel has not accepted yet. */
    public static final long UNFROZEN = -1L;

    /** Generation carried by an intent frozen while no world set was tracked. */
    public static final long UNTRACKED_EPOCH = 0L;

    /** Builds a draft the channel will freeze. */
    public static WriteIntent draft(long intentId, String srcWorldId, String dstWorldId,
                                    String dstDomainId, long expectedVersion, long worldEpoch,
                                    String payloadHandle, String waitPoint, String siteId) {
        return new WriteIntent(intentId, srcWorldId, dstWorldId, dstDomainId, expectedVersion,
            worldEpoch, UNFROZEN, payloadHandle, waitPoint, siteId);
    }

    /** Freezes this intent at the position the channel reached. */
    public WriteIntent withFrozenOrder(long order) {
        return new WriteIntent(intentId, srcWorldId, dstWorldId, dstDomainId, expectedVersion,
            worldEpoch, order, payloadHandle, waitPoint, siteId);
    }
}
