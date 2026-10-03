/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The whole-tick model of one entity class. The ownership fixture decides who runs a row; this
 * package answers what the row's tick does to the row itself, so a worker can compute that answer
 * without touching the entity and the host can commit it before the host would have ticked the row.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;
