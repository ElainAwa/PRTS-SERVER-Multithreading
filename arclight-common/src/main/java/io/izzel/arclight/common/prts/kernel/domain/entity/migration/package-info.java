/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The migration contract: one logical unit carrying the moved row with its passengers, leash,
 * projectiles and target across a world incarnation change, the old-owner stop, prefix record,
 * publish and new-owner grant in one order, and the death-drop add/pickup handoff. The package is
 * contract-only: no production path builds a unit, no world object moves and the live effect is not
 * claimed. Player pickup and the network boundary stay outside and are marked for the player work. */
package io.izzel.arclight.common.prts.kernel.domain.entity.migration;
