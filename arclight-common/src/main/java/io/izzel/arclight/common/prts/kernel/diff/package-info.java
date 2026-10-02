/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The state hash of a committed frame and the read-only comparison of the two arms. The hash is the
 * equivalence gate - whitelisted fields, fixed order, bit for bit - and the compare side only
 * locates the first fork, so it can never influence what was hashed. Design reference: the commit
 * and differential replay section.
 */
package io.izzel.arclight.common.prts.kernel.diff;
