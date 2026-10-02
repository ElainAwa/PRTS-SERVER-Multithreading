/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import java.util.List;
import java.util.Map;

public record CoverageReport(int registeredTotal, int unregistered, double coveragePct,
                             Map<String, Integer> injectionWalkthrough, long forcedConvergence,
                             int siteInventoryTotal, List<String> pendingElements,
                             int siteRegistered, int siteUnregistered, double siteCoveragePct,
                             List<String> sitePendingElements, List<String> siteUncoveredIds) {
}
