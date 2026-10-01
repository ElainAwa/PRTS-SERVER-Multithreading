/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * TaCZ interoperability: the respawn packet patch that repairs the gun state of that mod.
 *
 * <p>The patch watches the outgoing packet path and applies only while the gun mod is present; its
 * repair logic lives in a shared helper outside this package. It stands alone because it is that
 * mod's only patch, and any further patch of that mod belongs in this package. Design reference:
 * the release-facing mod compatibility rounds.</p>
 */
package io.izzel.arclight.common.prts.modsupport.tacz;
