/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The two-owner dormant wiring: two owner sites of one process share one OwnerContext (registry,
 * version slots, arena) and the one SharedRuntime pool, while their ring metadata, arena leases and
 * work accounts are accounted per owner. The whole path is off unless its internal property is set;
 * a build that injects nothing keeps one owner site and behaves exactly as before.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.twoowner;
