/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

/**
 * One frozen intent: the source, the target and the order the commit segment will run it in.
 *
 * <p>A caller builds a draft and the channel freezes it: the order is assigned when the channel
 * accepts the intent, under the same lock, so the orders inside the channel are contiguous and a
 * draft refused at the depth limit consumes none of them. Nothing recomputes the order afterwards,
 * and a caller-supplied value never overrides the one the channel froze.</p>
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
 * @param frozenOrder     position in the order the channel froze
 * @param payloadHandle   handle of the payload in the data plane
 * @param waitPoint       registered wait this intent waits on
 * @param siteId          site that produced the intent
 */
public record WriteIntent(long intentId, String srcWorldId, String dstWorldId, String dstDomainId,
                          long expectedVersion, long frozenOrder, String payloadHandle,
                          String waitPoint, String siteId) {

    /** Order of a draft the channel has not accepted yet. */
    public static final long UNFROZEN = -1L;

    /**
     * Builds a draft the channel will freeze.
     *
     * @param intentId        identity of this intent
     * @param srcWorldId      world the write came from
     * @param dstWorldId      world the write targets
     * @param dstDomainId     domain the write targets
     * @param expectedVersion version the intent expects to find
     * @param payloadHandle   handle of the payload in the data plane
     * @param waitPoint       registered wait this intent waits on
     * @param siteId          site that produced the intent
     * @return the draft, with its order still open
     */
    public static WriteIntent draft(long intentId, String srcWorldId, String dstWorldId,
                                    String dstDomainId, long expectedVersion, String payloadHandle,
                                    String waitPoint, String siteId) {
        return new WriteIntent(intentId, srcWorldId, dstWorldId, dstDomainId, expectedVersion,
            UNFROZEN, payloadHandle, waitPoint, siteId);
    }

    /**
     * Freezes this intent at the position the channel reached.
     *
     * @param order the position the channel froze
     * @return this intent with its order fixed
     */
    public WriteIntent withFrozenOrder(long order) {
        return new WriteIntent(intentId, srcWorldId, dstWorldId, dstDomainId, expectedVersion,
            order, payloadHandle, waitPoint, siteId);
    }
}
