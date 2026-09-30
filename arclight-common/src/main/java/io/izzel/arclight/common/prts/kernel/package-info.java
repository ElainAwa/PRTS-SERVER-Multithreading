/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The new kernel scaffolding: four pieces that observe or meter and never write the world.
 *
 * <p>The pieces live in the subpackages: {@code codes} is the refusal vocabulary every piece shares
 * and the bottom of the internal order; {@code config} reads the settings; {@code meter},
 * {@code intent}, {@code auth}, {@code shares} and {@code waitpoints} are the four pieces; this
 * package carries the module entry that drives them once per tick; and {@code observe} is the top
 * of the order and renders what they publish. A package may only depend on packages below it in
 * that order, which is what keeps the graph acyclic and readable.</p>
 *
 * <p>Everything under this package is off unless the kernel category is enabled, occupies no mixin
 * anchor and no world write path, and can be removed again by deleting this subtree and its one
 * platform listener.</p>
 */
package io.izzel.arclight.common.prts.kernel;
