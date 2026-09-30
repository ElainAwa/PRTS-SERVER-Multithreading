/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The controlled channel a write takes when it may not write directly.
 *
 * <p>An intent is frozen into a queue with the order it had at planning time, and the commit
 * segment walks exactly that order; this batch lands the shape and the ordering, not the world
 * write. The queue has an explicit depth and refuses at it, so pressure becomes a counted refusal
 * instead of unbounded growth.</p>
 */
package io.izzel.arclight.common.prts.kernel.intent;
