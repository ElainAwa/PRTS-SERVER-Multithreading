/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;

/**
 * The last decision a write path took, kept for a reader of the readout.
 *
 * <p>The five elements a diagnosis needs are here: the code, the site, the thread, the world and
 * the tick. One decision is kept rather than a trace, so the readout stays bounded while an
 * operator can still see why the most recent refusal happened.</p>
 *
 * @param path        the write point that judged
 * @param origin      thread the attempt came from
 * @param holder      where the writer comes from
 * @param disposition how the attempt left
 * @param code        refusal code, or {@code null} when none was attached
 * @param siteId      site identity of the writer
 * @param threadRef   readable identity of the writing thread
 * @param worldId     world the attempt targeted
 * @param tickIndex   tick the attempt belongs to
 */
public record WriteDecision(WritePath path, ThreadOrigin origin, HolderKind holder,
                            WriteDisposition disposition, RejectCode code, String siteId,
                            String threadRef, String worldId, long tickIndex) {
}
