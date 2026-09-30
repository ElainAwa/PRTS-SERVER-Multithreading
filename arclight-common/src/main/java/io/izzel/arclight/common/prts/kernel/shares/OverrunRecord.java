/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;

/**
 * One recorded overrun, with the action it would have taken.
 *
 * <p>The record is evidence, not a command: nothing reads it back into scheduling, and the executed
 * flag is written as false in this batch. Both dimension counts travel with it so a reader can see
 * that the class and the world dimension agree.</p>
 *
 * @param tickIndex         tick it happened at
 * @param worldId           world that overspent
 * @param overClass         class that overspent
 * @param siteId            site that was charged
 * @param overClassHits     class dimension count after this record
 * @param overWorldHits     world dimension count after this record
 * @param wouldDegradeLevel level a degradation would enter
 * @param actionExecuted    whether a degradation ran; this batch always reports false
 */
public record OverrunRecord(long tickIndex, String worldId, ShareClass overClass, String siteId,
                            long overClassHits, long overWorldHits,
                            DegradeLevel wouldDegradeLevel, boolean actionExecuted) {
}
