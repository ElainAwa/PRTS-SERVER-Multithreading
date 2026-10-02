/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import java.util.List;
import java.util.Map;

/** The report answers the questions a reader has about coverage in one pass: how many wait point
 * rows exist, how many of them carry all four elements, how many call sites the written-down list
 * holds, how many of those are complete, and which of them nothing covers. */
public record CoverageReport(int registeredTotal, int unregistered, double coveragePct,
                             Map<String, Integer> injectionWalkthrough, long forcedConvergence,
                             int siteInventoryTotal, List<String> pendingElements,
                             int siteRegistered, int siteUnregistered, double siteCoveragePct,
                             List<String> sitePendingElements, List<String> siteUncoveredIds) {
}
