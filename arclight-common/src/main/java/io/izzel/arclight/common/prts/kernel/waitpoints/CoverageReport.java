/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import java.util.List;
import java.util.Map;

/**
 * Everything the coverage self-check publishes about the registry.
 *
 * <p>The report answers three questions at once: how many rows exist, how many of them carry all
 * four elements, and which call sites were seen without a row. The walkthrough map counts fixture
 * runs per row, and the forced convergence count states whether this build can produce one at
 * all.</p>
 *
 * @param registeredTotal     rows in the registry
 * @param unregistered        call sites that no row covers
 * @param coveragePct         share of rows that carry all four elements
 * @param injectionWalkthrough walkthrough counts per row identity
 * @param forcedConvergence   forced convergence count; zero in this batch
 * @param siteInventoryTotal  call sites the inventory knows
 * @param pendingElements     rows that are not complete
 */
public record CoverageReport(int registeredTotal, int unregistered, double coveragePct,
                             Map<String, Integer> injectionWalkthrough, long forcedConvergence,
                             int siteInventoryTotal, List<String> pendingElements) {
}
