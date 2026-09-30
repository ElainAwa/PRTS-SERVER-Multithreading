/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The time-budget share table of one tick: shares, metering and the overrun verdict.
 *
 * <p>The table is planned once per tick from the world list, the tick index and the metered work;
 * it reads no clock, so the same input plans the same table. Overruns are counted in two dimensions
 * whose totals always agree, negative margins are published as they are, and a degradation is only
 * described: the executed flag stays false and the reserved column belongs to its two entries.</p>
 */
package io.izzel.arclight.common.prts.kernel.shares;
