/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The intermediate state a worker is allowed to write: one slot per batch, its generation and the
 * pin and release pairs that prove nothing leaked.
 *
 * <p>A worker never writes the world; it writes a slot of its own segment. The slot carries the
 * identity of the batch that owns it, so a write from any other batch is refused instead of landing;
 * releasing a slot bumps its generation first, so a result that arrives after the release cannot be
 * mistaken for the new owner's value. The package is separate because the data layout and its
 * lifetime rules are read by both the dispatcher and the merge, and neither should own the other's
 * copy of them.</p>
 *
 * <p>Design reference: the intermediate state and slot ownership sections of the kernel design
 * note.</p>
 */
package io.izzel.arclight.common.prts.kernel.arena;
