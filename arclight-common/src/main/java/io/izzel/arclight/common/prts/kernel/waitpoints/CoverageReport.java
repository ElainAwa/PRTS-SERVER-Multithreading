/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import java.util.List;
import java.util.Map;

/**
 * Everything the coverage self-check publishes about the registry.
 *
 * <p>The report answers the questions a reader has about coverage in one pass: how many wait point
 * rows exist, how many of them carry all four elements, how many call sites the written-down list
 * holds, how many of those are complete, and which of them nothing covers. The walkthrough map
 * counts fixture runs per row, and the forced convergence count states whether this build can
 * produce one at all.</p>
 *
 * @param registeredTotal      wait point rows in the registry
 * @param unregistered         waits seen at call sites no row covers
 * @param coveragePct          share of rows that carry all four elements
 * @param injectionWalkthrough walkthrough counts per row identity
 * @param forcedConvergence    forced convergence count; zero in this batch
 * @param siteInventoryTotal   call sites the written-down list holds
 * @param pendingElements      rows that are not complete
 * @param siteRegistered       call sites that carry all four elements
 * @param siteUnregistered     call sites seen at run time that the list does not hold
 * @param siteCoveragePct      share of listed call sites that carry all four elements
 * @param sitePendingElements  call sites that are not complete
 * @param siteUncoveredIds     listed call sites whose wait point row is not registered
 */
public record CoverageReport(int registeredTotal, int unregistered, double coveragePct,
                             Map<String, Integer> injectionWalkthrough, long forcedConvergence,
                             int siteInventoryTotal, List<String> pendingElements,
                             int siteRegistered, int siteUnregistered, double siteCoveragePct,
                             List<String> sitePendingElements, List<String> siteUncoveredIds) {
}
