/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

/** Level of a write right domain. A right is always held for one world and one level, so the key
 * of a token is the pair of the world and this level plus the domain identity inside the level. */
public enum WriteLevel {

    OBJECT,
    SECTION,
    REGION,
    DIMENSION
}
