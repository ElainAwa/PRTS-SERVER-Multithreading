/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

/**
 * One frozen intent: the source, the target and the order the commit segment will run it in.
 *
 * <p>The order is frozen at planning time and never recomputed; the wait point names the registered
 * wait the intent belongs to, so a reader can follow an intent back to the wait it came from. The
 * payload handle points into the data plane; nothing in this batch resolves it to a world write.</p>
 *
 * @param intentId        identity of this intent
 * @param srcWorldId      world the write came from
 * @param dstWorldId      world the write targets
 * @param dstDomainId     domain the write targets
 * @param expectedVersion version the intent expects to find
 * @param frozenOrder     position in the order frozen at planning time
 * @param payloadHandle   handle of the payload in the data plane
 * @param waitPoint       registered wait this intent waits on
 * @param siteId          site that produced the intent
 */
public record WriteIntent(long intentId, String srcWorldId, String dstWorldId, String dstDomainId,
                          long expectedVersion, long frozenOrder, String payloadHandle,
                          String waitPoint, String siteId) {
}
