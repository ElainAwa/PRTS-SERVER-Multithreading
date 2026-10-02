/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;

/** One recorded overrun, with the action it would have taken. The record is evidence, not a
 * command: nothing reads it back into scheduling, and the executed flag is written as false in
 * this batch. */
public record OverrunRecord(long tickIndex, String worldId, ShareClass overClass, String siteId,
                            long overClassHits, long overWorldHits,
                            DegradeLevel wouldDegradeLevel, boolean actionExecuted) {
}
