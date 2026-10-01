/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The controlled channel a write takes when it may not write directly, and the segment that walks it.
 *
 * <p>A routed write is frozen into the channel at the moment the channel accepts it, in the order it
 * arrived; the commit segment walks exactly that order at the end of a tick, on the thread that owns
 * the world. The package owns the whole deferral - the channel, its explicit depth, the frozen order
 * and the one spot where the deferred write finally lands - so that no caller can write the world
 * out of order or out of turn. It depends on the refusal vocabulary only.</p>
 *
 * <p>Design reference: the write right section of the kernel design note.</p>
 */
package io.izzel.arclight.common.prts.kernel.intent;
