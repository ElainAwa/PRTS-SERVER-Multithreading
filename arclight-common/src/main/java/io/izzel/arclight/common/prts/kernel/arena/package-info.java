/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The intermediate state a worker may write: one slot per batch, its generation, and the pin and
 * release pairs that prove nothing leaked. Separate because the layout and its lifetime rules are
 * read by both the dispatcher and the merge. Design reference: the arena section.
 */
package io.izzel.arclight.common.prts.kernel.arena;
