/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * PRTS category: optional ServerCore layer, implementation side.
 *
 * <p>Holds the classes the optional layer's mixin handlers call. They live here rather than under
 * {@code prts.optional.servercore} because that package tree belongs to a mixin configuration, and
 * Mixin refuses to load any class inside such a tree directly; a handler that referenced one would
 * fail at the first tick.</p>
 *
 * <p>Nothing here is applied on its own: the mixin configuration of the layer decides whether the
 * handlers run at all, and the journal switch inside it decides whether they do anything.</p>
 */
package io.izzel.arclight.common.prts.optional.journal;
