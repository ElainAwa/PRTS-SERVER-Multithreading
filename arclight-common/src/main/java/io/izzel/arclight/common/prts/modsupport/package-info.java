/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * PRTS category: interoperability with the registered mod set.
 *
 * <p>Coexistence with modernfix / create / ferritecore / Quark and friends: ordering, logger and
 * anchor conflicts. Nothing here may land on a new-kernel seam.</p>
 *
 * <p>Only mixins live in this package: Mixin refuses to load a class of it anywhere else, so a
 * helper both a mixin and its target need goes to {@code io.izzel.arclight.common.prts.support}.</p>
 */
package io.izzel.arclight.common.prts.modsupport;
