/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

/**
 * Level of a write right domain.
 *
 * <p>A right is always held for one world and one level, so the key of a token is the pair of the
 * world and this level plus the domain identity inside the level.</p>
 */
public enum WriteLevel {

    /** A single block, entity or block entity. */
    OBJECT,
    /** A chunk section, the smallest contiguous write unit. */
    SECTION,
    /** A connected chunk component; also a scheduling domain. */
    REGION,
    /** The whole world, including its lifecycle. */
    DIMENSION
}
