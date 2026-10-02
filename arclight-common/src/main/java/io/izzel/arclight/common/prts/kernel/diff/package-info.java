/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The state hash of a committed frame and the read-only comparison of the two arms.
 *
 * <p>The hash is the only gate of equivalence: it folds the whitelisted fields of the committed
 * intermediate state in a fixed order, bit for bit, and records the algorithm it used. The compare
 * side is a reader only - it looks at two hashes and locates the first fork down to one entity and
 * one field - so it can never influence what was hashed.</p>
 *
 * <p>Design reference: the state hash and differential replay sections of the kernel design
 * note.</p>
 */
package io.izzel.arclight.common.prts.kernel.diff;
