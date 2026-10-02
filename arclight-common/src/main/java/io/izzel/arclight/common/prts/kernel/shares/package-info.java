/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The time-budget share table of one tick: shares, metering and the overrun verdict. The table is
 * planned from the world list, the tick index and the metered work only, so the same input plans the
 * same table. Design reference: the share-table section.
 */
package io.izzel.arclight.common.prts.kernel.shares;
