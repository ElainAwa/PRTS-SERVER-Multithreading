/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

/**
 * One observed wait.
 *
 * <p>The wait point may be unknown: an observation is recorded either way, because a wait at a call
 * site no row covers is exactly what the coverage report has to show. The progress reading travels
 * with the span so the registry can publish it next to the row.</p>
 *
 * @param wpId            wait point that covers the wait, or {@code null} when none does
 * @param callSiteRef     call site the wait happened at
 * @param siteId          site that waited
 * @param worldId         world it happened in
 * @param tickIndex       tick it happened at
 * @param waitMs          duration of the wait
 * @param progressReading the progress value read at the end of the wait
 */
public record WaitSpan(String wpId, String callSiteRef, String siteId, String worldId,
                       long tickIndex, long waitMs, String progressReading) {
}
