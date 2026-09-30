/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;

/**
 * What a degradation would do about an overrun row.
 *
 * <p>This batch never executes the level it names: the value exists so the readout can state what
 * the next batch would enter, and the record next to it carries the executed flag that stays
 * false.</p>
 *
 * @param worldId      world that overspent
 * @param overClass    class that overspent
 * @param overWorld    whether the world dimension is over as well
 * @param level        level a degradation would enter
 */
public record WouldDegrade(String worldId, ShareClass overClass, boolean overWorld,
                           DegradeLevel level) {
}
