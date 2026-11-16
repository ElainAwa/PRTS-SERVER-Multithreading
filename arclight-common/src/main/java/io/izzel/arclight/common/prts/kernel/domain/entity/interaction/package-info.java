/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The cross-entity interaction contract: the attacker/victim pair, the health, inventory,
 * durability and point-of-interest record keys it touches, the all-or-none admission of those
 * resources and the deterministic pair order. The package is contract-only: no production path
 * builds an interaction, no world object is written, and the live effect is not claimed. */
package io.izzel.arclight.common.prts.kernel.domain.entity.interaction;
