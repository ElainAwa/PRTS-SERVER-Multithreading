/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

/**
 * One frozen intent: the source, the target and the order the commit segment will run it in.
 *
 * <p>A caller builds a draft and the channel freezes it: the order is assigned when the channel
 * accepts the intent, under the same lock, so the orders inside one world's shard are contiguous and
 * a draft refused at the depth limit consumes none of them. Nothing recomputes the order afterwards,
 * and a caller-supplied value never overrides the one the channel froze.</p>
 *
 * <p>The intent also carries the world generation it was frozen under. A deferred write holds the
 * world object it was built for, and that object can outlive the world; the generation is the part of
 * the world identity a comparison can still trust at the commit, and the commit refuses an intent
 * whose world is gone or has been rebuilt since.</p>
 *
 * <p>The wait point names the registered wait the intent belongs to, so a reader can follow an
 * intent back to the wait it came from. The payload handle points into the data plane, where the
 * deferred write itself is held until the commit segment reaches it.</p>
 *
 * @param intentId        identity of this intent
 * @param srcWorldId      world the write came from
 * @param dstWorldId      world the write targets
 * @param dstDomainId     domain the write targets
 * @param expectedVersion version the intent expects to find
 * @param worldEpoch      generation of the target world at the moment the intent was frozen
 * @param frozenOrder     position in the order the channel froze
 * @param payloadHandle   handle of the payload in the data plane
 * @param waitPoint       registered wait this intent waits on
 * @param siteId          site that produced the intent
 */
public record WriteIntent(long intentId, String srcWorldId, String dstWorldId, String dstDomainId,
                          long expectedVersion, long worldEpoch, long frozenOrder,
                          String payloadHandle, String waitPoint, String siteId) {

    /** Order of a draft the channel has not accepted yet. */
    public static final long UNFROZEN = -1L;

    /** Generation carried by an intent frozen while no world set was tracked. */
    public static final long UNTRACKED_EPOCH = 0L;

    /**
     * Builds a draft the channel will freeze.
     *
     * @param intentId        identity of this intent
     * @param srcWorldId      world the write came from
     * @param dstWorldId      world the write targets
     * @param dstDomainId     domain the write targets
     * @param expectedVersion version the intent expects to find
     * @param worldEpoch      generation of the target world, or {@link #UNTRACKED_EPOCH}
     * @param payloadHandle   handle of the payload in the data plane
     * @param waitPoint       registered wait this intent waits on
     * @param siteId          site that produced the intent
     * @return the draft, with its order still open
     */
    public static WriteIntent draft(long intentId, String srcWorldId, String dstWorldId,
                                    String dstDomainId, long expectedVersion, long worldEpoch,
                                    String payloadHandle, String waitPoint, String siteId) {
        return new WriteIntent(intentId, srcWorldId, dstWorldId, dstDomainId, expectedVersion,
            worldEpoch, UNFROZEN, payloadHandle, waitPoint, siteId);
    }

    /**
     * Freezes this intent at the position the channel reached.
     *
     * @param order the position the channel froze
     * @return this intent with its order fixed
     */
    public WriteIntent withFrozenOrder(long order) {
        return new WriteIntent(intentId, srcWorldId, dstWorldId, dstDomainId, expectedVersion,
            worldEpoch, order, payloadHandle, waitPoint, siteId);
    }
}
