/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;

public record OverrunRecord(long tickIndex, String worldId, ShareClass overClass, String siteId,
                            long overClassHits, long overWorldHits,
                            DegradeLevel wouldDegradeLevel, boolean actionExecuted) {
}
