/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The new kernel scaffolding: four pieces that observe or meter and never write the world.
 *
 * <p>The four pieces are the write decision point ({@code auth}), the intent channel behind it
 * ({@code intent}), the per-class self timer ({@code meter}), the time-budget share table
 * ({@code shares}) and the wait point registry ({@code waitpoints}); the readout they publish lives
 * in {@code observe}. {@link io.izzel.arclight.common.prts.kernel.KernelModule} is the single entry
 * the platform drives once per tick.</p>
 *
 * <p>Everything under this package is off unless the kernel category is enabled, occupies no mixin
 * anchor and no world write path, and can be removed again by deleting this subtree and its one
 * platform listener.</p>
 */
package io.izzel.arclight.common.prts.kernel;
